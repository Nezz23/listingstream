package dev.nezz.rentguard.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The Ollama wire format, against a fake server: what we send and how tool calls are read back. */
class OllamaChatModelTest {

    @Test
    void sendsToolsDeterministicallyAndParsesToolCalls() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {"model":"m","message":{"role":"assistant","content":"",
                     "tool_calls":[{"function":{"name":"top_hosts","arguments":{"borough":"Queens","limit":3}}}]},
                     "done":true}""".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            JsonMapper json = JsonMapper.builder().build();
            OllamaChatModel model = new OllamaChatModel("http://127.0.0.1:" + server.getAddress().getPort(),
                    "qwen2.5:7b", json);

            ChatModel.Reply reply = model.chat(List.of(Map.of("role", "user", "content", "hi")),
                    List.of(Map.of("type", "function")));

            assertThat(reply.toolCalls()).singleElement().satisfies(c -> {
                assertThat(c.name()).isEqualTo("top_hosts");
                assertThat(c.arguments()).containsEntry("borough", "Queens").containsEntry("limit", 3);
            });
            assertThat(reply.message()).containsEntry("role", "assistant");
            var sent = json.readTree(request.get());
            assertThat(sent.path("model").asString()).isEqualTo("qwen2.5:7b");
            assertThat(sent.path("stream").asBoolean()).isFalse();
            assertThat(sent.path("options").path("temperature").asInt()).isZero();
            assertThat(model.name()).isEqualTo("ollama/qwen2.5:7b");
        } finally {
            server.stop(0);
        }
    }
}
