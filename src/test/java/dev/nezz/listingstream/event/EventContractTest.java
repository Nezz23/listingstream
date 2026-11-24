package dev.nezz.listingstream.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EventContractTest {

    static ListingEvent event(String snapshot, String id, String minNights, String license) {
        return new ListingEvent(snapshot, id, "Cozy room", "42", "Harlem", "Manhattan", "Private room in home",
                "Private room", "2", minNights, "365", null, license, "$1,250.00", "40.81", "-73.94");
    }

    @Test
    void aCleanEventIsTyped() {
        ListingRecord r = EventContract.validate(event("2026-11-01", "123", "30", null));
        assertThat(r.snapshotDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(r.listingId()).isEqualTo(123);
        assertThat(r.hostId()).isEqualTo(42);
        assertThat(r.priceUsd()).isEqualByComparingTo(new BigDecimal("1250.00"));
        assertThat(r.latitude()).isEqualTo(40.81);
        assertThat(r.instantBookable()).isNull();
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "OSE-STRREG-0001075, 2,    registered",
            "ose-strreg-0001075, 2,    registered",
            "'  Exempt ',        2,    exempt",
            "NULL,               30,   long_term_30_plus",
            "NULL,               29,   unregistered_short_term",
            "'   ',              1,    unregistered_short_term",
            "NULL,               NULL, unknown",
            "NULL,               abc,  unknown",
            "LL18 pending,       5,    unregistered_short_term"})
    void localLaw18StatusFollowsTheWarehouseRule(String license, String minNights, String expected) {
        assertThat(EventContract.validate(event("2026-11-01", "1", minNights, license)).ll18Status())
                .isEqualTo(expected);
    }

    @Test
    void unparseableSoftFieldsBecomeNullLikeTryCast() {
        ListingEvent e = new ListingEvent("2026-11-01", "7", null, "not-a-host", null, null, null, null, "two",
                "", "∞", "maybe", "", "free", "north", null);
        ListingRecord r = EventContract.validate(e);
        assertThat(r.hostId()).isNull();
        assertThat(r.accommodates()).isNull();
        assertThat(r.minimumNights()).isNull();
        assertThat(r.maximumNights()).isNull();
        assertThat(r.instantBookable()).isNull();
        assertThat(r.license()).isNull();
        assertThat(r.priceUsd()).isNull();
        assertThat(r.latitude()).isNull();
    }

    @Test
    void anEventWithoutAUsableIdIsRefusedButKeepsItsSnapshot() {
        assertThatThrownBy(() -> EventContract.validate(event("2026-11-01", "abc", "30", null)))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("listing id")
                .satisfies(e -> assertThat(((InvalidEventException) e).snapshotDate())
                        .isEqualTo(LocalDate.of(2026, 11, 1)));
        assertThatThrownBy(() -> EventContract.validate(event("2026-11-01", "-5", "30", null)))
                .isInstanceOf(InvalidEventException.class);
    }

    @Test
    void anEventWithoutASnapshotDateIsRefused() {
        assertThatThrownBy(() -> EventContract.validate(event("yesterday", "1", "30", null)))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("snapshotDate");
    }
}
