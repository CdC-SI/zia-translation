package zas.admin.zia.translation.service;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AiClientConfigurationTest {

    private static final String COMPLETION_RESPONSE = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"test-model",
             "choices":[{"index":0,"message":{"role":"assistant","content":"bonjour"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
            """;

    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> authorizationHeader = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = COMPLETION_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void chatClientCallsOpenAiCompatibleEndpointUnderV1() {
        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        ChatClient client = new AiClientConfiguration()
                .llmChatClient(baseUrl, "test-model", "secret-key", ObservationRegistry.NOOP);

        String content = client.prompt().user("hello").call().content();

        assertThat(content).isEqualTo("bonjour");
        assertThat(requestPath.get()).isEqualTo("/v1/chat/completions");
        assertThat(authorizationHeader.get()).isEqualTo("Bearer secret-key");
        assertThat(requestBody.get()).contains("\"model\":\"test-model\"");
    }

    @Test
    void baseUrlIsNormalizedToIncludeApiVersion() {
        assertThat(AiClientConfiguration.toOpenAiSdkBaseUrl("http://host:8000")).isEqualTo("http://host:8000/v1");
        assertThat(AiClientConfiguration.toOpenAiSdkBaseUrl("http://host:8000/")).isEqualTo("http://host:8000/v1");
        assertThat(AiClientConfiguration.toOpenAiSdkBaseUrl("http://host:8000/v1")).isEqualTo("http://host:8000/v1");
        assertThat(AiClientConfiguration.toOpenAiSdkBaseUrl("http://host:8000/v1/")).isEqualTo("http://host:8000/v1");
    }
}
