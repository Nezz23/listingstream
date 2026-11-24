package dev.nezz.rentguard.ai;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The guard against invented numbers: every number in an answer must also appear in the question, a tool argument
 * or a tool result. Citations like [R4] are checked separately: each must be a passage the tools actually returned. Formatting differences (1,477 vs 1477, 12.0 vs 12) are not.
 */
public final class Grounding {

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");
    private static final Pattern CITATION = Pattern.compile("\\[R\\d+]");

    private Grounding() {
    }

    public static Set<String> numbers(String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = NUMBER.matcher(CITATION.matcher(text).replaceAll(" "));
        while (m.find()) {
            String digits = m.group().replace(",", "");
            if (digits.endsWith(".")) {
                digits = digits.substring(0, digits.length() - 1);
            }
            found.add(new BigDecimal(digits).stripTrailingZeros().toPlainString());
        }
        return found;
    }

    /** Rule ids cited as [R#] in the answer. */
    public static Set<String> citations(String answer) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = CITATION.matcher(answer);
        while (m.find()) {
            ids.add(m.group().substring(1, m.group().length() - 1));
        }
        return ids;
    }

    /** Numbers in {@code answer} that no evidence contains; empty means the answer is grounded. */
    public static Set<String> ungrounded(String answer, Collection<String> evidence) {
        Set<String> allowed = new LinkedHashSet<>();
        evidence.forEach(e -> allowed.addAll(numbers(e)));
        Set<String> missing = new LinkedHashSet<>(numbers(answer));
        missing.removeAll(allowed);
        return missing;
    }
}
