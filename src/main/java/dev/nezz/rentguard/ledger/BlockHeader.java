package dev.nezz.rentguard.ledger;

import java.time.LocalDate;

/** The signed part of a block. Its hash is the block's identity and the next block's prevHash. */
public record BlockHeader(int height, LocalDate snapshotDate, String prevHash, String merkleRoot, int entryCount,
                          long createdMs) {

    public static final String GENESIS_PREV = "0".repeat(64);

    public String canonical() {
        return height + "|" + snapshotDate + "|" + prevHash + "|" + merkleRoot + "|" + entryCount + "|" + createdMs;
    }

    public String hash() {
        return Hashes.sha256Hex(canonical());
    }
}
