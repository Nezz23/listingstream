package dev.nezz.listingstream.history;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Compares the stream's results with numbers exported from the batch dbt warehouse built on the same raw files:
 * which snapshots were accepted or quarantined, listings per compliance status in every snapshot, and the size of
 * the Type 2 history. Every check must match exactly.
 */
@Service
public class ReconciliationService {

    public record Check(String name, String expected, String actual, boolean match) {
    }

    public record Report(boolean allMatch, int checks, int mismatches, List<Check> details) {
    }

    private final JdbcTemplate jdbc;
    private final JsonNode reference;

    public ReconciliationService(JdbcTemplate jdbc, JsonMapper json) throws IOException {
        this.jdbc = jdbc;
        try (InputStream in = new ClassPathResource("reference_counts.json").getInputStream()) {
            this.reference = json.readTree(in);
        }
    }

    public Report run() {
        List<Check> checks = new ArrayList<>();
        checks.add(check("accepted snapshots", list(reference.get("accepted_snapshots")),
                jdbc.queryForList("select snapshot_date::text from snapshots where status = 'applied' order by 1",
                        String.class).toString()));
        checks.add(check("quarantined snapshots", list(reference.get("quarantined_snapshots")),
                jdbc.queryForList("select snapshot_date::text from snapshots where status = 'quarantined' order by 1",
                        String.class).toString()));
        checks.add(check("history versions", reference.get("scd2").get("versions").asText(),
                jdbc.queryForObject("select count(*) from listing_versions", Long.class).toString()));
        checks.add(check("listings with history", reference.get("scd2").get("listings").asText(),
                jdbc.queryForObject("select count(distinct listing_id) from listing_versions", Long.class).toString()));
        checks.add(check("current versions", reference.get("scd2_current").asText(),
                jdbc.queryForObject("select count(*) from listing_versions where is_current", Long.class).toString()));
        for (Map.Entry<String, JsonNode> snapshot : reference.get("ll18_counts").properties()) {
            Map<String, Long> actual = new LinkedHashMap<>();
            jdbc.query("""
                    select ll18_status, count(*) from staged_listings where snapshot_date = ?
                    group by 1 order by 1""", rs -> {
                actual.put(rs.getString(1), rs.getLong(2));
            }, Date.valueOf(LocalDate.parse(snapshot.getKey())));
            Map<String, Long> expected = new LinkedHashMap<>();
            snapshot.getValue().properties().forEach(e -> expected.put(e.getKey(), e.getValue().asLong()));
            checks.add(check("LL18 counts " + snapshot.getKey(), expected.toString(), actual.toString()));
        }
        int mismatches = (int) checks.stream().filter(c -> !c.match()).count();
        return new Report(mismatches == 0, checks.size(), mismatches, checks);
    }

    private static String list(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asText()));
        return values.toString();
    }

    private static Check check(String name, String expected, String actual) {
        return new Check(name, expected, actual, expected.equals(actual));
    }
}
