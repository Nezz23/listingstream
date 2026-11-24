package dev.nezz.rentguard.api;

import dev.nezz.rentguard.ledger.LedgerKeys;
import dev.nezz.rentguard.ledger.LedgerProofs;
import dev.nezz.rentguard.ledger.LedgerVerifier;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ledger")
public class LedgerController {

    private final JdbcTemplate jdbc;
    private final LedgerVerifier verifier;
    private final LedgerProofs proofs;
    private final LedgerKeys keys;

    public LedgerController(JdbcTemplate jdbc, LedgerVerifier verifier, LedgerProofs proofs, LedgerKeys keys) {
        this.jdbc = jdbc;
        this.verifier = verifier;
        this.proofs = proofs;
        this.keys = keys;
    }

    @GetMapping("/blocks")
    public List<Map<String, Object>> blocks() {
        return jdbc.queryForList("""
                select b.height, b.snapshot_date, b.entry_count, b.prev_hash, b.merkle_root, b.block_hash,
                       e.detail as decision
                from audit_blocks b join audit_entries e on e.block_height = b.height and e.position = 0
                order by b.height""");
    }

    @GetMapping("/verify")
    public LedgerVerifier.Report verify() {
        return verifier.verify();
    }

    @GetMapping("/public-key")
    public Map<String, String> publicKey() {
        return Map.of("algorithm", "ECDSA P-256 with SHA-256, P1363 signature encoding", "spki",
                keys.publicKeyBase64());
    }

    @GetMapping("/listings/{id}")
    public List<LedgerProofs.Receipt> receipts(@PathVariable long id) {
        List<LedgerProofs.Receipt> found = proofs.forListing(id);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no ledger entries for listing " + id);
        }
        return found;
    }
}
