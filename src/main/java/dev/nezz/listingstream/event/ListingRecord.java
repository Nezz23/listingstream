package dev.nezz.listingstream.event;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A listing event that passed the contract: typed, license normalized, Local Law 18 status assigned. */
public record ListingRecord(
        LocalDate snapshotDate,
        long listingId,
        String name,
        Long hostId,
        String neighbourhood,
        String borough,
        String propertyType,
        String roomType,
        Integer accommodates,
        Integer minimumNights,
        Integer maximumNights,
        Boolean instantBookable,
        String license,
        String ll18Status,
        BigDecimal priceUsd,
        Double latitude,
        Double longitude) {
}
