package dev.nezz.rentguard.ingest;

import dev.nezz.rentguard.event.ListingRecord;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Bulk writes for the hot path. JPA is used for reads; inserts at stream speed go through JDBC batches. */
@Repository
public class StagingRepository {

    private static final String INSERT = """
            insert into staged_listings (snapshot_date, listing_id, name, host_id, neighbourhood, borough,
                property_type, room_type, accommodates, minimum_nights, maximum_nights, instant_bookable, license,
                ll18_status, price_usd, latitude, longitude)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (snapshot_date, listing_id) do nothing""";

    private final JdbcTemplate jdbc;

    public StagingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns how many rows were new (duplicates from redelivery or replay are skipped by the primary key). */
    public int insert(List<ListingRecord> records) {
        int[] counts = jdbc.batchUpdate(INSERT, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ListingRecord r = records.get(i);
                ps.setDate(1, Date.valueOf(r.snapshotDate()));
                ps.setLong(2, r.listingId());
                ps.setString(3, r.name());
                setLong(ps, 4, r.hostId());
                ps.setString(5, r.neighbourhood());
                ps.setString(6, r.borough());
                ps.setString(7, r.propertyType());
                ps.setString(8, r.roomType());
                setInt(ps, 9, r.accommodates());
                setInt(ps, 10, r.minimumNights());
                setInt(ps, 11, r.maximumNights());
                if (r.instantBookable() == null) {
                    ps.setNull(12, Types.BOOLEAN);
                } else {
                    ps.setBoolean(12, r.instantBookable());
                }
                ps.setString(13, r.license());
                ps.setString(14, r.ll18Status());
                ps.setBigDecimal(15, r.priceUsd());
                setDouble(ps, 16, r.latitude());
                setDouble(ps, 17, r.longitude());
            }

            @Override
            public int getBatchSize() {
                return records.size();
            }
        });
        int inserted = 0;
        for (int c : counts) {
            inserted += Math.max(c, 0);
        }
        return inserted;
    }

    public void deadLetter(LocalDate snapshot, String topic, int partition, long offset, String key, String payload,
                           String error) {
        jdbc.update("""
                insert into dead_letters (snapshot_date, source_topic, source_partition, source_offset, event_key,
                    payload, error) values (?, ?, ?, ?, ?, ?, ?)
                on conflict (source_topic, source_partition, source_offset) do nothing""",
                snapshot == null ? null : Date.valueOf(snapshot), topic, partition, offset, key, payload, error);
    }

    public void recordMarker(LocalDate snapshot, long expected) {
        jdbc.update("""
                insert into snapshots (snapshot_date, expected_events) values (?, ?)
                on conflict (snapshot_date) do update set expected_events = excluded.expected_events
                where snapshots.status = 'pending'""", Date.valueOf(snapshot), expected);
    }

    private static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.BIGINT);
        } else {
            ps.setLong(i, v);
        }
    }

    private static void setInt(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.INTEGER);
        } else {
            ps.setInt(i, v);
        }
    }

    private static void setDouble(PreparedStatement ps, int i, Double v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.DOUBLE);
        } else {
            ps.setDouble(i, v);
        }
    }
}
