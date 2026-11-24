package dev.nezz.rentguard.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every argument a model sends is validated before it reaches SQL. */
class InspectorToolsTest {

    @Test
    void boroughsAreMatchedCaseInsensitivelyAndAnythingElseIsRefused() {
        assertThat(InspectorTools.borough(Map.of("borough", "staten island"))).isEqualTo("Staten Island");
        assertThat(InspectorTools.borough(Map.of("borough", "NYC"))).isNull();
        assertThat(InspectorTools.borough(Map.of())).isNull();
        assertThatThrownBy(() -> InspectorTools.borough(Map.of("borough", "Manhattan' or 1=1 --")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown borough");
    }

    @Test
    void statusesLimitsIdsAndDatesAreChecked() {
        assertThat(InspectorTools.status(Map.of("status", "Exempt"))).isEqualTo("exempt");
        assertThatThrownBy(() -> InspectorTools.status(Map.of("status", "illegal")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(InspectorTools.limit(Map.of())).isEqualTo(5);
        assertThat(InspectorTools.limit(Map.of("limit", "3"))).isEqualTo(3);
        assertThatThrownBy(() -> InspectorTools.limit(Map.of("limit", 500))).hasMessageContaining("1 to 20");
        assertThat(InspectorTools.listingId(Map.of("listing_id", 8490))).isEqualTo(8490L);
        assertThatThrownBy(() -> InspectorTools.listingId(Map.of("listing_id", "8490; drop table x")))
                .hasMessageContaining("whole number");
        assertThat(InspectorTools.snapshot(Map.of("snapshot", ""))).isNull();
        assertThat(InspectorTools.snapshot(Map.of("snapshot", "2026-06-08"))).isEqualTo(LocalDate.parse("2026-06-08"));
        assertThatThrownBy(() -> InspectorTools.snapshot(Map.of("snapshot", "June 8")))
                .hasMessageContaining("YYYY-MM-DD");
    }
}
