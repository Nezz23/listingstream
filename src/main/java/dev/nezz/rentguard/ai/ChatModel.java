package dev.nezz.rentguard.ai;

import java.util.List;
import java.util.Map;

/** The one thing the assistant needs from a language model: a chat turn that may ask for tool calls. */
public interface ChatModel {

    record ToolCall(String name, Map<String, Object> arguments) {
    }

    /** {@code message} is the assistant message exactly as it must be sent back in the next turn. */
    record Reply(String content, List<ToolCall> toolCalls, Map<String, Object> message) {
    }

    Reply chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools);

    String name();
}
