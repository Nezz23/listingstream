package dev.nezz.rentguard.ledger;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-derives the whole ledger from the stored rows: every entry hash from its fields, every Merkle root, every
 * header hash, every link to the previous block and every signature. Stops at the first block that fails.
 */
@Service
public class LedgerVerifier {

    public record Report(boolean valid, int blocks, long entries, Integer firstInvalidBlock, String reason,
                         String tipHash, long millis) {
    }

    private record StoredBlock(BlockHeader header, String blockHash, String signature) {
    }

    private final JdbcTemplate jdbc;
    private final LedgerKeys keys;

    public LedgerVerifier(JdbcTemplate jdbc, LedgerKeys keys) {
        this.jdbc = jdbc;
        this.keys = keys;
    }

    @Transactional(readOnly = true)
    public Report verify() {
        long started = System.nanoTime();
        List<StoredBlock> blocks = jdbc.query("""
                select height, snapshot_date, prev_hash, merkle_root, entry_count, created_ms, block_hash, signature
                from audit_blocks order by height""",
                (rs, i) -> new StoredBlock(new BlockHeader(rs.getInt(1), rs.getDate(2).toLocalDate(),
                        rs.getString(3), rs.getString(4), rs.getInt(5), rs.getLong(6)), rs.getString(7),
                        rs.getString(8)));
        String prev = BlockHeader.GENESIS_PREV;
        long entries = 0;
        for (int i = 0; i < blocks.size(); i++) {
            StoredBlock b = blocks.get(i);
            String problem = check(b, i, prev);
            entries += b.header().entryCount();
            if (problem != null) {
                return new Report(false, blocks.size(), entries, b.header().height(), problem, null, ms(started));
            }
            prev = b.blockHash();
        }
        long decided = jdbc.queryForObject("select count(*) from snapshots where status <> 'pending'", Long.class);
        if (decided != blocks.size()) {
            return new Report(false, blocks.size(), entries, blocks.size(),
                    decided + " snapshots were decided but the ledger has " + blocks.size() + " blocks", null,
                    ms(started));
        }
        return new Report(true, blocks.size(), entries, null, null, blocks.isEmpty() ? null : prev, ms(started));
    }

    private String check(StoredBlock b, int expectedHeight, String expectedPrev) {
        BlockHeader h = b.header();
        if (h.height() != expectedHeight) {
            return "expected height " + expectedHeight + " but found " + h.height();
        }
        if (!h.prevHash().equals(expectedPrev)) {
            return "does not link to the previous block";
        }
        List<String> hashes = new ArrayList<>(h.entryCount());
        LocalDate day = h.snapshotDate();
        List<String> mismatch = new ArrayList<>(1);
        jdbc.query("""
                select position, kind, listing_id, old_status, new_status, detail, entry_hash
                from audit_entries where block_height = ? order by position""",
                rs -> {
                    Long listing = rs.getObject(3) == null ? null : rs.getLong(3);
                    String hash = new AuditEntry(rs.getString(2), listing, rs.getString(4), rs.getString(5),
                            rs.getString(6)).hash(day);
                    if (mismatch.isEmpty() && (rs.getInt(1) != hashes.size() || !hash.equals(rs.getString(7)))) {
                        mismatch.add("entry " + rs.getInt(1) + " does not match its recorded hash"
                                + (listing == null ? "" : " (listing " + listing + ")"));
                    }
                    hashes.add(hash);
                }, h.height());
        if (!mismatch.isEmpty()) {
            return mismatch.getFirst();
        }
        if (hashes.size() != h.entryCount()) {
            return "header says " + h.entryCount() + " entries but " + hashes.size() + " are stored";
        }
        if (!MerkleTree.root(hashes).equals(h.merkleRoot())) {
            return "Merkle root does not match the entries";
        }
        if (!h.hash().equals(b.blockHash())) {
            return "block hash does not match the header";
        }
        if (!keys.verify(b.blockHash(), b.signature())) {
            return "signature is not valid for the ledger key";
        }
        return null;
    }

    private static long ms(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
