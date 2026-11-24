package dev.nezz.rentguard.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GroundingTest {

    @Test
    void numbersAreComparedByValueNotFormatting() {
        assertThat(Grounding.numbers("29,873 listings, 5.0% and 12.50 and 2026-11-01."))
                .containsExactly("29873", "5", "12.5", "2026", "11", "1");
    }

    @Test
    void anAnswerIsGroundedOnlyIfEveryNumberIsInTheEvidence() {
        List<String> evidence = List.of("{\"listings\":1477,\"percent\":4.2}");
        assertThat(Grounding.ungrounded("1,477 listings (4.2%).", evidence)).isEmpty();
        assertThat(Grounding.ungrounded("About 1,500 listings, roughly 4%.", evidence)).containsExactly("1500", "4");
    }

    @Test
    void citationsAreExtractedAndNotMistakenForNumbers() {
        assertThat(Grounding.citations("Hosts register [R2]; platforms check [R3] and [R2].")).containsExactly("R2", "R3");
        assertThat(Grounding.ungrounded("See [R12].", List.of())).isEmpty();
    }
}
