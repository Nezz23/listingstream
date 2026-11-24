package dev.nezz.rentguard.ai;

import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way the model touches data: six fixed, parameterized, read-only queries. The model chooses a tool and
 * fills in arguments; every argument is validated here, so a prompt cannot turn into arbitrary SQL.
 */
@Component
public class InspectorTools {

    static final Set<String> BOROUGHS = Set.of("Bronx", "Brooklyn", "Manhattan", "Queens", "Staten Island");
    static final Set<String> STATUSES = Set.of("registered", "exempt", "long_term_30_plus", "unregistered_short_term",
            "unknown");

    private final JdbcTemplate jdbc;

    public InspectorTools(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** JSON-schema tool definitions in the shape Ollama and OpenAI-style APIs expect. */
    public List<Map<String, Object>> definitions() {
        Map<String, Object> borough = Map.of("type", "string", "enum", List.copyOf(BOROUGHS),
                "description", "NYC borough; omit for the whole city");
        Map<String, Object> status = Map.of("type", "string", "enum", List.copyOf(STATUSES));
        return List.of(
                tool("compliance_counts", "Number and share of listings in each Local Law 18 status for one applied "
                        + "snapshot (default: the latest), citywide or for one borough.",
                        Map.of("borough", borough, "snapshot", Map.of("type", "string",
                                "description", "snapshot date YYYY-MM-DD; omit for the latest")), List.of()),
                tool("top_hosts", "Hosts with the most current listings in a status (default "
                        + "unregistered_short_term), optionally in one borough.",
                        Map.of("borough", borough, "status", status, "limit", Map.of("type", "integer",
                                "description", "1 to 20, default 5")), List.of()),
                tool("listing_history", "Every recorded version of one listing: status, license, minimum nights and "
                        + "the dates each version was valid.",
                        Map.of("listing_id", Map.of("type", "integer")), List.of("listing_id")),
                tool("status_trend", "For every applied snapshot: total listings and unregistered short-term "
                        + "listings, citywide.", Map.of(), List.of()),
                tool("snapshot_decisions", "Every scrape with its decision (applied or quarantined), the reason, "
                        + "and its audit ledger block.", Map.of(), List.of()),
                tool("search_rules", "Retrieve rule passages about Local Law 18 and how RentGuard classifies "
                        + "listings. Cite them as [R#].", Map.of("query", Map.of("type", "string")),
                        List.of("query")));
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties,
                                            List<String> required) {
        return Map.of("type", "function", "function", Map.of("name", name, "description", description,
                "parameters", Map.of("type", "object", "properties", properties, "required", required)));
    }

    @Transactional(readOnly = true)
    public Object call(String name, Map<String, Object> args) {
        return switch (name) {
            case "compliance_counts" -> complianceCounts(borough(args), snapshot(args));
            case "top_hosts" -> topHosts(borough(args), status(args), limit(args));
            case "listing_history" -> listingHistory(listingId(args));
            case "status_trend" -> jdbc.queryForList("""
                    select s.snapshot_date::text as snapshot, count(*) as listings,
                           count(*) filter (where s.ll18_status = 'unregistered_short_term') as unregistered_short_term
                    from staged_listings s join snapshots p on p.snapshot_date = s.snapshot_date
                    where p.status = 'applied' group by 1 order by 1""");
            case "snapshot_decisions" -> jdbc.queryForList("""
                    select p.snapshot_date::text as snapshot, p.status, p.reason, p.staged_rows as rows,
                           b.height as ledger_block, b.entry_count as ledger_entries
                    from snapshots p left join audit_blocks b on b.snapshot_date = p.snapshot_date
                    order by 1""");
            case "search_rules" -> searchRules(text(args, "query"));
            default -> throw new IllegalArgumentException("unknown tool " + name);
        };
    }

    private Map<String, Object> complianceCounts(String borough, LocalDate snapshot) {
        LocalDate day = snapshot != null ? snapshot : jdbc.queryForObject(
                "select max(snapshot_date) from snapshots where status = 'applied'", LocalDate.class);
        if (day == null) {
            throw new IllegalArgumentException("no applied snapshot yet");
        }
        String status = jdbc.queryForList("select status from snapshots where snapshot_date = ?", String.class,
                Date.valueOf(day)).stream().findFirst().orElse(null);
        if (!"applied".equals(status)) {
            throw new IllegalArgumentException("snapshot " + day + " is " + (status == null ? "not known" : status)
                    + "; only applied snapshots have counts");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshot", day.toString());
        out.put("borough", borough == null ? "all of NYC" : borough);
        long[] total = {0};
        List<Map<String, Object>> rows = jdbc.queryForList("""
                with c as (
                    select ll18_status, count(*) as n from staged_listings
                    where snapshot_date = ? and (cast(? as text) is null or borough = ?) group by 1)
                select s.status, coalesce(c.n, 0) as listings,
                       round(100.0 * coalesce(c.n, 0) / nullif(sum(coalesce(c.n, 0)) over (), 0), 1) as percent
                from unnest(array['unregistered_short_term', 'registered', 'exempt', 'long_term_30_plus', 'unknown'])
                     as s(status)
                left join c on c.ll18_status = s.status
                order by 2 desc, 1""", Date.valueOf(day), borough, borough); // zero counts are answers too
        rows.forEach(r -> total[0] += ((Number) r.get("listings")).longValue());
        out.put("total_listings", total[0]);
        out.put("by_status", rows);
        return out;
    }

    private Map<String, Object> topHosts(String borough, String status, int limit) {
        String s = status == null ? "unregistered_short_term" : status;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", s);
        out.put("borough", borough == null ? "all of NYC" : borough);
        out.put("hosts", jdbc.queryForList("""
                select host_id, count(*) as listings, string_agg(distinct borough, ', ') as boroughs
                from listing_versions
                where is_current and ll18_status = ? and host_id is not null
                  and (cast(? as text) is null or borough = ?)
                group by host_id order by listings desc, host_id limit ?""", s, borough, borough, limit));
        return out;
    }

    private Object listingHistory(long id) {
        List<Map<String, Object>> versions = jdbc.queryForList("""
                select listing_id, ll18_status as status, license, minimum_nights, borough, neighbourhood, host_id,
                       valid_from::text as valid_from, coalesce(valid_to::text, 'current') as valid_to
                from listing_versions where listing_id = ? order by valid_from""", id);
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("no listing " + id);
        }
        // Consecutive versions often share a status (a price or name changed). Merge them so the model reads
        // status periods directly instead of reasoning across rows.
        List<Map<String, Object>> periods = new java.util.ArrayList<>();
        for (Map<String, Object> v : versions) {
            Map<String, Object> last = periods.isEmpty() ? null : periods.getLast();
            if (last != null && last.get("status").equals(v.get("status")) && last.get("to").equals(v.get("valid_from"))) {
                last.put("to", v.get("valid_to"));
            } else {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("status", v.get("status"));
                p.put("from", v.get("valid_from"));
                p.put("to", v.get("valid_to"));
                periods.add(p);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("listing_id", id);
        out.put("status_periods", periods);
        out.put("versions", versions);
        return out;
    }

    private List<Map<String, Object>> searchRules(String query) {
        return jdbc.queryForList("""
                with q as (select array_to_string(tsvector_to_array(to_tsvector('english', ?)), ' | ') as terms)
                select id, title, body from rule_passages, q
                where q.terms <> '' and tsv @@ to_tsquery('english', q.terms)
                order by ts_rank(tsv, to_tsquery('english', q.terms)) desc, id limit 4""", query);
    }

    static String borough(Map<String, Object> args) {
        String raw = text(args, "borough");
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("all") || raw.equalsIgnoreCase("nyc")) {
            return null;
        }
        return BOROUGHS.stream().filter(b -> b.equalsIgnoreCase(raw.strip())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown borough " + raw + "; use one of " + BOROUGHS));
    }

    static String status(Map<String, Object> args) {
        String raw = text(args, "status");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.strip().toLowerCase(Locale.ROOT);
        if (!STATUSES.contains(s)) {
            throw new IllegalArgumentException("unknown status " + raw + "; use one of " + STATUSES);
        }
        return s;
    }

    static int limit(Map<String, Object> args) {
        Object raw = args.get("limit");
        int n = raw == null ? 5 : (int) number(raw, "limit");
        if (n < 1 || n > 20) {
            throw new IllegalArgumentException("limit must be 1 to 20");
        }
        return n;
    }

    static long listingId(Map<String, Object> args) {
        Object raw = args.get("listing_id");
        if (raw == null) {
            throw new IllegalArgumentException("listing_id is required");
        }
        return number(raw, "listing_id");
    }

    static LocalDate snapshot(Map<String, Object> args) {
        String raw = text(args, "snapshot");
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("latest")) {
            return null;
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("snapshot must be YYYY-MM-DD");
        }
    }

    private static long number(Object raw, String field) {
        if (raw instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(raw.toString().strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a whole number");
        }
    }

    private static String text(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : v.toString();
    }
}
