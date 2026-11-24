package dev.nezz.rentguard.event;

/** Sent after the last event of a scrape: tells the consumer how many events belong to that snapshot. */
public record SnapshotMarker(String snapshotDate, long expectedEvents) {
}
