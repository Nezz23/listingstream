package dev.nezz.rentguard.ledger;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends one signed block per snapshot decision. It runs inside the decision's own database transaction (and under
 * its advisory lock), so a snapshot is never applied without its block, and blocks are appended one at a time.
 */
@Service
public class AuditLedger {

    public record AppendResult(int height, String blockHash, int entries) {
    }

    private final JdbcTemplate jdbc;
    private final LedgerKeys keys;

    public AuditLedger(JdbcTemplate jdbc, LedgerKeys keys) {
        this.jdbc = jdbc;
        this.keys = keys;
    }

    /** Call after the snapshot's versions were merged: records the decision and every status change it caused. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AppendResult recordApplied(LocalDate snapshot, String detail) {
        List<AuditEntry> entries = new ArrayList<>();
        entries.add(new AuditEntry("snapshot", null, null, null, "applied: " + detail));
        Date day = Date.valueOf(snapshot);
        jdbc.query("""
                select coalesce(n.listing_id, o.listing_id), o.ll18_status, n.ll18_status
                from (select listing_id, ll18_status from listing_versions where valid_from = ?) n
                full join (select listing_id, ll18_status from listing_versions where valid_to = ?) o
                       on o.listing_id = n.listing_id
                where o.ll18_status is distinct from n.ll18_status
                order by 1""",
                rs -> {
                    entries.add(new AuditEntry("status_change", rs.getLong(1), rs.getString(2), rs.getString(3),
                            null));
                }, day, day);
        return append(snapshot, entries);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AppendResult recordQuarantined(LocalDate snapshot, String reason) {
        return append(snapshot, List.of(new AuditEntry("snapshot", null, null, null, "quarantined: " + reason)));
    }

    private AppendResult append(LocalDate snapshot, List<AuditEntry> entries) {
        List<Map<String, Object>> tip = jdbc.queryForList(
                "select height, block_hash from audit_blocks order by height desc limit 1");
        int height = tip.isEmpty() ? 0 : ((Number) tip.getFirst().get("height")).intValue() + 1;
        String prev = tip.isEmpty() ? BlockHeader.GENESIS_PREV : (String) tip.getFirst().get("block_hash");

        List<String> hashes = new ArrayList<>(entries.size());
        for (AuditEntry e : entries) {
            hashes.add(e.hash(snapshot));
        }
        BlockHeader header = new BlockHeader(height, snapshot, prev, MerkleTree.root(hashes), entries.size(),
                System.currentTimeMillis());
        String blockHash = header.hash();
        jdbc.update("""
                insert into audit_blocks (height, snapshot_date, prev_hash, merkle_root, entry_count, created_ms,
                    block_hash, signature) values (?, ?, ?, ?, ?, ?, ?, ?)""",
                height, Date.valueOf(snapshot), prev, header.merkleRoot(), entries.size(), header.createdMs(),
                blockHash, keys.sign(blockHash));

        List<Object[]> rows = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            AuditEntry e = entries.get(i);
            rows.add(new Object[]{height, i, e.kind(), e.listingId(), e.oldStatus(), e.newStatus(), e.detail(),
                    hashes.get(i)});
        }
        jdbc.batchUpdate("""
                insert into audit_entries (block_height, position, kind, listing_id, old_status, new_status, detail,
                    entry_hash) values (?, ?, ?, ?, ?, ?, ?, ?)""", rows);
        return new AppendResult(height, blockHash, entries.size());
    }
}
