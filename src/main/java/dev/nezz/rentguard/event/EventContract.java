package dev.nezz.rentguard.event;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * The per-event data contract. Hard failures (no snapshot date, no numeric listing id) make an event unusable and
 * send it to the dead-letter topic. Soft fields follow the batch warehouse's TRY_CAST semantics: a value that does
 * not parse becomes null instead of rejecting the listing, so stream and batch agree row for row.
 */
public final class EventContract {

    private EventContract() {
    }

    public static ListingRecord validate(ListingEvent e) {
        if (e == null) {
            throw new InvalidEventException("empty event", null);
        }
        LocalDate snapshot = parseDate(e.snapshotDate());
        if (snapshot == null) {
            throw new InvalidEventException("snapshotDate missing or not an ISO date: " + e.snapshotDate(), null);
        }
        Long id = parseLong(e.id());
        if (id == null || id <= 0) {
            throw new InvalidEventException("listing id missing or not a positive integer: " + e.id(), snapshot);
        }
        Integer minimumNights = parseInt(e.minimumNights());
        String license = normalizeLicense(e.license());
        return new ListingRecord(snapshot, id, e.name(), parseLong(e.hostId()), e.neighbourhood(), e.borough(),
                e.propertyType(), e.roomType(), parseInt(e.accommodates()), minimumNights,
                parseInt(e.maximumNights()), parseBool(e.instantBookable()), license,
                ll18Status(license, minimumNights), parseMoney(e.price()), parseDouble(e.latitude()),
                parseDouble(e.longitude()));
    }

    /**
     * New York City Local Law 18: a short-term rental (under 30 nights) must show a registration number
     * (OSE-STRREG-...) or an exemption. Same rule as the warehouse's ll18_status macro.
     */
    public static String ll18Status(String license, Integer minimumNights) {
        if (license != null && license.startsWith("OSE-STRREG-")) {
            return "registered";
        }
        if ("EXEMPT".equals(license)) {
            return "exempt";
        }
        if (minimumNights == null) {
            return "unknown";
        }
        return minimumNights >= 30 ? "long_term_30_plus" : "unregistered_short_term";
    }

    /** Hosts type registration numbers by hand; compare case-insensitively and treat blanks as missing. */
    static String normalizeLicense(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip().toUpperCase(java.util.Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    static LocalDate parseDate(String raw) {
        try {
            return raw == null ? null : LocalDate.parse(raw.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static Long parseLong(String raw) {
        try {
            return raw == null ? null : Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Integer parseInt(String raw) {
        try {
            return raw == null ? null : Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double parseDouble(String raw) {
        try {
            return raw == null ? null : Double.parseDouble(raw.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Boolean parseBool(String raw) {
        if ("t".equals(raw)) {
            return true;
        }
        return "f".equals(raw) ? false : null;
    }

    /** "$1,234.00" -> 1234.00 */
    static BigDecimal parseMoney(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw.replace("$", "").replace(",", "").strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
