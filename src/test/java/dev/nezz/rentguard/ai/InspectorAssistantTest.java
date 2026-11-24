package dev.nezz.rentguard.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The agent loop and its guard, with a scripted model and mocked tools: no LLM or database needed. */
class InspectorAssistantTest {

    /** Plays back canned replies and records every conversation it was sent. */
    static class ScriptedModel implements ChatModel {
        final Deque<Reply> replies = new ArrayDeque<>();
        final List<List<Map<String, Object>>> seen = new ArrayList<>();

        ScriptedModel then(String content, ToolCall... calls) {
            replies.add(new Reply(content, List.of(calls), Map.of("role", "assistant", "content", content)));
            return this;
        }

        @Override
        public Reply chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
            seen.add(List.copyOf(messages));
            return replies.removeFirst();
        }

        @Override
        public String name() {
            return "scripted";
        }
    }

    final InspectorTools tools = mock(InspectorTools.class);
    final ScriptedModel model = new ScriptedModel();
    final InspectorAssistant assistant = new InspectorAssistant(model, tools, JsonMapper.builder().build());

    static ChatModel.ToolCall call(String name, Map<String, Object> args) {
        return new ChatModel.ToolCall(name, args);
    }

    @Test
    void aGroundedAnswerIsReleasedWithItsToolTrail() {
        when(tools.call(eq("compliance_counts"), anyMap())).thenReturn(Map.of("unregistered_short_term", 56));
        model.then("", call("compliance_counts", Map.of("borough", "Brooklyn")))
                .then("Brooklyn has 56 unregistered short-term listings.");

        var answer = assistant.ask("How many unregistered listings in Brooklyn?");

        assertThat(answer.grounded()).isTrue();
        assertThat(answer.corrections()).isZero();
        assertThat(answer.steps()).extracting(InspectorAssistant.Step::tool).containsExactly("compliance_counts");
        // The tool result went back to the model as a tool message.
        assertThat(model.seen.get(1).getLast()).containsEntry("role", "tool");
    }

    @Test
    void anInventedNumberIsSentBackAndTheCorrectedAnswerIsReleased() {
        when(tools.call(eq("compliance_counts"), anyMap())).thenReturn(Map.of("unregistered_short_term", 56));
        model.then("", call("compliance_counts", Map.of()))
                .then("About 60 listings.")
                .then("56 listings.");

        var answer = assistant.ask("How many?");

        assertThat(answer.grounded()).isTrue();
        assertThat(answer.answer()).isEqualTo("56 listings.");
        assertThat(answer.corrections()).isEqualTo(1);
        assertThat(answer.rejectedDraft()).isEqualTo("About 60 listings.");
        assertThat(model.seen.get(2).getLast().get("content").toString())
                .contains("not in any tool result: 60").contains("original question");
    }

    @Test
    void aModelThatKeepsInventingIsBlockedInsteadOfReleased() {
        model.then("Roughly 1,000 illegal bookings.").then("Still about 900.");

        var answer = assistant.ask("Estimate illegal bookings.");

        assertThat(answer.grounded()).isFalse();
        assertThat(answer.answer()).startsWith("I couldn't check that answer");
        assertThat(answer.ungroundedNumbers()).containsExactly("900");
    }

    @Test
    void aCitationToAPassageThatWasNeverRetrievedIsRejected() {
        when(tools.call(eq("search_rules"), anyMap()))
                .thenReturn(List.of(Map.of("id", "R2", "title", "Host registration", "body", "...")));
        model.then("Hosts must register [R3].")
                .then("", call("search_rules", Map.of("query", "host registration")))
                .then("Hosts must register [R2].");

        var answer = assistant.ask("What must hosts do?");

        assertThat(answer.grounded()).isTrue();
        assertThat(answer.answer()).isEqualTo("Hosts must register [R2].");
        assertThat(model.seen.get(1).getLast().get("content").toString()).contains("You cited R3");
    }

    @Test
    void aBadToolArgumentIsReportedToTheModelSoItCanRetry() {
        when(tools.call(eq("compliance_counts"), eq(Map.of("borough", "Gotham"))))
                .thenThrow(new IllegalArgumentException("unknown borough Gotham"));
        model.then("", call("compliance_counts", Map.of("borough", "Gotham")))
                .then("There is no borough called Gotham.");

        var answer = assistant.ask("How many listings in Gotham?");

        assertThat(answer.steps().getFirst().error()).isEqualTo("unknown borough Gotham");
        assertThat(model.seen.get(1).getLast().get("content").toString()).startsWith("error: unknown borough");
        assertThat(answer.grounded()).isTrue();
    }

    @Test
    void theToolBudgetIsFinite() {
        when(tools.call(eq("status_trend"), anyMap())).thenReturn(List.of());
        for (int i = 0; i < InspectorAssistant.MAX_TOOL_ROUNDS; i++) {
            model.then("", call("status_trend", Map.of()));
        }
        model.then("No trend data yet.");

        var answer = assistant.ask("Trend?");

        assertThat(answer.steps()).hasSize(InspectorAssistant.MAX_TOOL_ROUNDS);
        assertThat(answer.grounded()).isTrue();
    }
}
