package dev.nezz.listingstream.history;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides one complete snapshot inside a single database transaction: quarantine it, or merge it into the
 * Type 2 history with three set-based statements. A PostgreSQL advisory lock serializes decisions even if several
 * app instances share the database.
 */
@Service
public class SnapshotApplier {

    /** Same volume rule as the batch pipeline: a scrape with under half the rows of the last good one is partial. */
    static final double MAX_VOLUME_DROP = 0.5;

    private static final String TRACKED_CHANGED = """
            v.name is distinct from s.name or v.host_id is distinct from s.host_id
            or v.neighbourhood is distinct from s.neighbourhood or v.borough is distinct from s.borough
            or v.property_type is distinct from s.property_type or v.room_type is distinct from s.room_type
            or v.accommodates is distinct from s.accommodates or v.minimum_nights is distinct from s.minimum_nights
            or v.maximum_nights is distinct from s.maximum_nights
            or v.instant_bookable is distinct from s.instant_bookable
            or v.license is distinct from s.license or v.ll18_status is distinct from s.ll18_status""";

    private final JdbcTemplate jdbc;

    public SnapshotApplier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    @CacheEvict(cacheNames = {"compliance", "trend"}, allEntries = true)
    public String apply(LocalDate snapshot) {
        long started = System.nanoTime();
        jdbc.queryForObject("select pg_advisory_xact_lock(7311)", Object.class);
        Date day = Date.valueOf(snapshot);
        long staged = jdbc.queryForObject("select count(*) from staged_listings where snapshot_date = ?",
                Long.class, day);
        List<Object[]> last = jdbc.query("""
                select snapshot_date, staged_rows from snapshots where status = 'applied'
                order by snapshot_date desc limit 1""", (rs, i) -> new Object[]{rs.getDate(1), rs.getLong(2)});

        if (!last.isEmpty()) {
            LocalDate lastDate = ((Date) last.getFirst()[0]).toLocalDate();
            long lastRows = (Long) last.getFirst()[1];
            if (!snapshot.isAfter(lastDate)) {
                return quarantine(day, staged, "out of order: arrived after " + lastDate + " was applied");
            }
            if (staged < lastRows * (1 - MAX_VOLUME_DROP)) {
                long dropPct = Math.round(100.0 * (lastRows - staged) / lastRows);
                return quarantine(day, staged, "volume: " + staged + " rows is a " + dropPct
                        + "% drop vs last applied (" + lastRows + "); limit 50%");
            }
        }

        int changed = jdbc.update("update listing_versions v set valid_to = ?, is_current = false "
                + "from staged_listings s where s.snapshot_date = ? and v.listing_id = s.listing_id "
                + "and v.is_current and (" + TRACKED_CHANGED + ")", day, day);
        int disappeared = jdbc.update("""
                update listing_versions v set valid_to = ?, is_current = false
                where v.is_current and not exists (
                    select 1 from staged_listings s where s.snapshot_date = ? and s.listing_id = v.listing_id)""",
                day, day);
        int opened = jdbc.update("""
                insert into listing_versions (listing_id, name, host_id, neighbourhood, borough, property_type,
                    room_type, accommodates, minimum_nights, maximum_nights, instant_bookable, license, ll18_status,
                    valid_from)
                select s.listing_id, s.name, s.host_id, s.neighbourhood, s.borough, s.property_type, s.room_type,
                    s.accommodates, s.minimum_nights, s.maximum_nights, s.instant_bookable, s.license,
                    s.ll18_status, s.snapshot_date
                from staged_listings s
                where s.snapshot_date = ?
                  and not exists (select 1 from listing_versions v where v.listing_id = s.listing_id and v.is_current)""",
                day);
        long millis = (System.nanoTime() - started) / 1_000_000;
        jdbc.update("""
                update snapshots set status = 'applied', staged_rows = ?, versions_opened = ?, versions_closed = ?,
                    merge_millis = ?, decided_at = now() where snapshot_date = ?""",
                staged, opened, changed + disappeared, millis, day);
        return "applied";
    }

    private String quarantine(Date day, long staged, String reason) {
        jdbc.update("""
                update snapshots set status = 'quarantined', reason = ?, staged_rows = ?, decided_at = now()
                where snapshot_date = ?""", reason, staged, day);
        return "quarantined";
    }
}
