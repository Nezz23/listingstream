package dev.nezz.rentguard.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers an inspector's question with a small agent loop: the model calls read-only tools, then writes an answer.
 * The answer is released only if every number in it traces back to the question or to tool output, and every [R#]
 * citation is a passage the model actually retrieved; otherwise the
 * model gets one chance to correct itself, and after that the assistant declines rather than guess.
 */
@Service
public class InspectorAssistant {

    static final int MAX_TOOL_ROUNDS = 5;

    static final String SYSTEM = """
            You are RentGuard's assistant for New York City short-term rental inspectors.
            Data: public Inside Airbnb scrapes of NYC listings, classified under Local Law 18.
            Rules:
            - Get every number from a tool. Never estimate, round differently, or compute new numbers yourself.
            - Whenever the question asks what something means, why, or what the rules say, call search_rules and cite
              the passages it returns like [R2]. Never cite a passage you did not retrieve.
            - If the tools cannot answer (for example names, phone numbers or addresses), say so plainly.
            - Answer in at most 4 short sentences or a short list.""";

    public record Step(String tool, Map<String, Object> arguments, Object result, String error) {
    }

    public record Answer(String question, String answer, boolean grounded, Set<String> ungroundedNumbers,
                         Set<String> unsupportedCitations, int corrections, String rejectedDraft, List<Step> steps, String model,
                         long millis) {
    }

    private final ChatModel model;
    private final InspectorTools tools;
    private final JsonMapper json;

    public InspectorAssistant(ChatModel model, InspectorTools tools, JsonMapper json) {
        this.model = model;
        this.tools = tools;
        this.json = json;
    }

    public Answer ask(String question) {
        long started = System.nanoTime();
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM));
        messages.add(Map.of("role", "user", "content", question));
        List<Step> steps = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        evidence.add(question);
        Set<String> retrieved = new LinkedHashSet<>();

        boolean retried = false;
        String rejectedDraft = null;
        for (int round = 0; round <= MAX_TOOL_ROUNDS + 1; round++) {
            ChatModel.Reply reply = model.chat(messages, round < MAX_TOOL_ROUNDS ? tools.definitions() : List.of());
            messages.add(reply.message());
            if (!reply.toolCalls().isEmpty() && round < MAX_TOOL_ROUNDS) {
                for (ChatModel.ToolCall call : reply.toolCalls()) {
                    Step step = run(call);
                    steps.add(step);
                    if (step.result() instanceof List<?> rows && "search_rules".equals(call.name())) {
                        rows.forEach(r -> retrieved.add(String.valueOf(((Map<?, ?>) r).get("id"))));
                    }
                    String content = step.error() != null ? "error: " + step.error() : json.writeValueAsString(step.result());
                    evidence.add(json.writeValueAsString(call.arguments()));
                    evidence.add(content);
                    messages.add(Map.of("role", "tool", "tool_name", call.name(), "content", content));
                }
                continue;
            }
            String answer = reply.content().strip();
            Set<String> missing = Grounding.ungrounded(answer, evidence);
            Set<String> badCitations = new LinkedHashSet<>(Grounding.citations(answer));
            badCitations.removeAll(retrieved);
            if (missing.isEmpty() && badCitations.isEmpty() && !answer.isEmpty()) {
                return new Answer(question, answer, true, Set.of(), Set.of(), retried ? 1 : 0, rejectedDraft, steps,
                        model.name(), ms(started));
            }
            if (retried) {
                return new Answer(question, "I couldn't check that answer against the data, so I'm not giving it. "
                        + "Try asking about one borough, one status or one listing.", false, missing, badCitations, 1,
                        rejectedDraft, steps, model.name(), ms(started));
            }
            retried = true;
            rejectedDraft = answer;
            List<String> problems = new ArrayList<>();
            if (answer.isEmpty()) {
                problems.add("Please answer the question using the tool results.");
            }
            if (!missing.isEmpty()) {
                problems.add("These numbers in your answer are not in any tool result: " + String.join(", ", missing)
                        + ". Use only numbers that appear in tool results.");
            }
            if (!badCitations.isEmpty()) {
                problems.add("You cited " + String.join(", ", badCitations) + " but did not retrieve "
                        + (badCitations.size() == 1 ? "it" : "them") + ". Call search_rules first, and cite only "
                        + "passages it returns.");
            }
            problems.add("Then answer my original question again in full: " + question);
            messages.add(Map.of("role", "user", "content", String.join(" ", problems)));
        }
        return new Answer(question, "I couldn't answer that within the tool budget.", false, Set.of(), Set.of(),
                retried ? 1 : 0, rejectedDraft, steps, model.name(), ms(started));
    }

    private Step run(ChatModel.ToolCall call) {
        Map<String, Object> args = call.arguments() == null ? Map.of() : new LinkedHashMap<>(call.arguments());
        try {
            return new Step(call.name(), args, tools.call(call.name(), args), null);
        } catch (IllegalArgumentException e) {
            return new Step(call.name(), args, null, e.getMessage()); // the model sees the error and can fix its call
        }
    }

    private static long ms(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
