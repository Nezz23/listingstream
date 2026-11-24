package dev.nezz.rentguard.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LedgerKeysTest {

    @TempDir
    Path dir;

    @Test
    void signaturesVerifyAndSurviveARestart() {
        LedgerKeys keys = new LedgerKeys(dir);
        String hash = Hashes.sha256Hex("block");
        String signature = keys.sign(hash);
        assertThat(keys.verify(hash, signature)).isTrue();

        LedgerKeys reloaded = new LedgerKeys(dir); // same files, same key
        assertThat(reloaded.publicKeyBase64()).isEqualTo(keys.publicKeyBase64());
        assertThat(reloaded.verify(hash, signature)).isTrue();
    }

    @Test
    void anotherKeyOrAnotherHashOrGarbageIsRejected(@TempDir Path other) {
        LedgerKeys keys = new LedgerKeys(dir);
        String hash = Hashes.sha256Hex("block");
        String signature = new LedgerKeys(other).sign(hash);
        assertThat(keys.verify(hash, signature)).isFalse();
        assertThat(keys.verify(Hashes.sha256Hex("other"), keys.sign(hash))).isFalse();
        assertThat(keys.verify(hash, "not base64!")).isFalse();
    }

    @Test
    void signaturesUseTheFixedLengthEncodingBrowsersExpect() {
        // P1363 = r || s, 32 bytes each for P-256; DER would be 70-72 bytes and WebCrypto would reject it.
        String signature = new LedgerKeys(dir).sign(Hashes.sha256Hex("block"));
        assertThat(java.util.Base64.getDecoder().decode(signature)).hasSize(64);
    }

    @Test
    void entriesAndHeadersHashAnUnambiguousCanonicalForm() {
        LocalDate day = LocalDate.parse("2026-06-14");
        AuditEntry delisted = new AuditEntry("status_change", 42L, "registered", null, null);
        assertThat(delisted.canonical(day)).isEqualTo("2026-06-14|status_change|42|registered|-|-");
        // A '|' inside a field cannot be used to shift fields around.
        assertThat(new AuditEntry("snapshot", null, null, null, "a|b").canonical(day)).endsWith("|a/b");
        assertThat(delisted.hash(day)).isNotEqualTo(new AuditEntry("status_change", 42L, null, "registered", null)
                .hash(day));

        BlockHeader header = new BlockHeader(0, day, BlockHeader.GENESIS_PREV, Hashes.sha256Hex("x"), 1, 5L);
        assertThat(header.hash()).isEqualTo(Hashes.sha256Hex(header.canonical()));
        assertThat(header.hash()).isNotEqualTo(new BlockHeader(0, day, BlockHeader.GENESIS_PREV,
                Hashes.sha256Hex("x"), 1, 6L).hash());
    }
}
