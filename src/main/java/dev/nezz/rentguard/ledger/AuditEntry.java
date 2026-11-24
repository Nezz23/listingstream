package dev.nezz.rentguard.ledger;

import java.time.LocalDate;

/**
 * One ledger entry. {@code kind = snapshot} records the snapshot decision (listingId null); {@code status_change}
 * records one listing moving between Local Law 18 statuses (oldStatus null = first seen, newStatus null = delisted).
 */
public record AuditEntry(String kind, Long listingId, String oldStatus, String newStatus, String detail) {

    /** The exact bytes that are hashed. Fields are separated by '|' and nulls written as '-', so it is unambiguous. */
    public String canonical(LocalDate snapshot) {
        return String.join("|", snapshot.toString(), kind, field(listingId), field(oldStatus), field(newStatus),
                field(detail));
    }

    public String hash(LocalDate snapshot) {
        return Hashes.sha256Hex(canonical(snapshot));
    }

    private static String field(Object value) {
        return value == null ? "-" : value.toString().replace("|", "/");
    }
}
