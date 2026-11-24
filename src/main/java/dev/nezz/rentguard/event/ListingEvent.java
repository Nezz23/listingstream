package dev.nezz.rentguard.event;

/**
 * One listing in one scrape, exactly as Inside Airbnb published it: every field is raw text. Typing happens in the
 * consumer ({@link EventContract}), so a malformed value is caught where it can be dead-lettered.
 */
public record ListingEvent(
        String snapshotDate,
        String id,
        String name,
        String hostId,
        String neighbourhood,
        String borough,
        String propertyType,
        String roomType,
        String accommodates,
        String minimumNights,
        String maximumNights,
        String instantBookable,
        String license,
        String price,
        String latitude,
        String longitude) {
}
