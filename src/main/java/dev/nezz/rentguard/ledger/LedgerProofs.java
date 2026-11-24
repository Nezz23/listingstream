package dev.nezz.rentguard.ledger;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-contained receipts: for each ledger entry about a listing, the entry, its Merkle path and the signed block
 * header. Anyone holding the public key can check a receipt without access to the database.
 */
@Service
public class LedgerProofs {

    public record Receipt(int blockHeight, LocalDate snapshotDate, int position, AuditEntry entry, String canonical,
                          String entryHash, List<MerkleTree.ProofStep> proof, BlockHeader header,
                          String headerCanonical, String blockHash, String signature) {
    }

    private final JdbcTemplate jdbc;

    public LedgerProofs(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Receipt> forListing(long listingId) {
        List<int[]> refs = jdbc.query(
                "select block_height, position from audit_entries where listing_id = ? order by block_height",
                (rs, i) -> new int[]{rs.getInt(1), rs.getInt(2)}, listingId);
        List<Receipt> receipts = new ArrayList<>(refs.size());
        for (int[] ref : refs) {
            receipts.add(receipt(ref[0], ref[1]));
        }
        return receipts;
    }

    Receipt receipt(int height, int position) {
        record Row(BlockHeader header, String blockHash, String signature) {
        }
        Row block = jdbc.queryForObject("""
                select height, snapshot_date, prev_hash, merkle_root, entry_count, created_ms, block_hash, signature
                from audit_blocks where height = ?""",
                (rs, i) -> new Row(new BlockHeader(rs.getInt(1), rs.getDate(2).toLocalDate(), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getLong(6)), rs.getString(7), rs.getString(8)), height);
        List<String> leaves = jdbc.queryForList(
                "select entry_hash from audit_entries where block_height = ? order by position", String.class, height);
        AuditEntry entry = jdbc.queryForObject("""
                select kind, listing_id, old_status, new_status, detail from audit_entries
                where block_height = ? and position = ?""",
                (rs, i) -> new AuditEntry(rs.getString(1), rs.getObject(2) == null ? null : rs.getLong(2),
                        rs.getString(3), rs.getString(4), rs.getString(5)), height, position);
        LocalDate day = block.header().snapshotDate();
        return new Receipt(height, day, position, entry, entry.canonical(day), entry.hash(day),
                MerkleTree.proof(leaves, position), block.header(), block.header().canonical(), block.blockHash(),
                block.signature());
    }
}
