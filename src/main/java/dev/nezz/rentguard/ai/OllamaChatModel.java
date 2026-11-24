package dev.nezz.rentguard.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Ollama's /api/chat with tool calling. Runs a local open model, so no data leaves the machine and no key is needed. */
@Component
public class OllamaChatModel implements ChatModel {

    private final RestClient http;
    private final String model;
    private final JsonMapper json;

    public OllamaChatModel(@Value("${rentguard.ai.base-url}") String baseUrl, @Value("${rentguard.ai.model}") String model,
                           JsonMapper json) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(180));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.model = model;
        this.json = json;
    }

    @Override
    public Reply chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("tools", tools);
        body.put("stream", false);
        body.put("options", Map.of("temperature", 0, "seed", 7));
        String raw = http.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(body)).retrieve().body(String.class);
        JsonNode message = json.readTree(raw).path("message");
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode fn = call.path("function");
            @SuppressWarnings("unchecked")
            Map<String, Object> args = json.convertValue(fn.path("arguments"), Map.class);
            calls.add(new ToolCall(fn.path("name").asString(), args == null ? Map.of() : args));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> asSent = json.convertValue(message, Map.class);
        return new Reply(message.path("content").asString(""), calls, asSent);
    }

    @Override
    public String name() {
        return "ollama/" + model;
    }
}
