package dev.nezz.rentguard.history;

import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Aggregates over applied snapshots. Cached; the cache is cleared whenever a snapshot is applied. */
@Service
public class ComplianceService {

    public record TrendPoint(LocalDate snapshot, long listings, long unregisteredShortTerm, double shareShortTerm) {
    }

    private final JdbcTemplate jdbc;

    public ComplianceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Listings per Local Law 18 status in one applied snapshot, optionally for one borough. */
    @Cacheable(cacheNames = "compliance", key = "#snapshot + '|' + #borough")
    public Map<String, Long> compliance(LocalDate snapshot, String borough) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("""
                select s.ll18_status, count(*) from staged_listings s
                join snapshots p on p.snapshot_date = s.snapshot_date and p.status = 'applied'
                where s.snapshot_date = ? and (cast(? as text) is null or s.borough = ?)
                group by 1 order by 1""",
                rs -> {
                    counts.put(rs.getString(1), rs.getLong(2));
                }, Date.valueOf(snapshot), borough, borough);
        return counts;
    }

    @Cacheable("trend")
    public List<TrendPoint> trend() {
        return jdbc.query("""
                select s.snapshot_date, count(*),
                       count(*) filter (where s.ll18_status = 'unregistered_short_term'),
                       avg(case when s.minimum_nights < 30 then 1.0 else 0.0 end)
                from staged_listings s
                join snapshots p on p.snapshot_date = s.snapshot_date and p.status = 'applied'
                group by 1 order by 1""",
                (rs, i) -> new TrendPoint(rs.getDate(1).toLocalDate(), rs.getLong(2), rs.getLong(3),
                        Math.round(rs.getDouble(4) * 1000) / 1000.0));
    }
}
