package dev.nezz.rentguard.history;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Snapshots are decided strictly in date order, and only once all their events have arrived: staged rows plus
 * dead-lettered events must reach the count in the marker. Events and markers travel on different topics and
 * partitions, so either can arrive first; counting makes the order irrelevant.
 */
@Service
public class SnapshotProcessor {

    private static final Logger log = LoggerFactory.getLogger(SnapshotProcessor.class);

    private final JdbcTemplate jdbc;
    private final SnapshotApplier applier;
    private final MeterRegistry meters;

    public SnapshotProcessor(JdbcTemplate jdbc, SnapshotApplier applier, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.applier = applier;
        this.meters = meters;
    }

    public synchronized void tryApply() {
        while (true) {
            List<Object[]> pending = jdbc.query("""
                    select snapshot_date, expected_events from snapshots where status = 'pending'
                    order by snapshot_date limit 1""", (rs, i) -> new Object[]{rs.getDate(1), rs.getLong(2)});
            if (pending.isEmpty()) {
                return;
            }
            Date day = (Date) pending.getFirst()[0];
            long expected = (Long) pending.getFirst()[1];
            long arrived = jdbc.queryForObject("""
                    select (select count(*) from staged_listings where snapshot_date = ?)
                         + (select count(*) from dead_letters where snapshot_date = ?)""", Long.class, day, day);
            if (arrived < expected) {
                return; // still streaming in; a later batch will call again
            }
            LocalDate snapshot = day.toLocalDate();
            String outcome = applier.apply(snapshot);
            meters.counter("rentguard.snapshots", "outcome", outcome).increment();
            log.info("snapshot {} {} ({} events)", snapshot, outcome, expected);
        }
    }
}
