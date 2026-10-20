package zas.admin.zia.translation.service.extraction;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import zas.admin.zia.translation.service.image.VisionImagePreprocessor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the request really sent to an OpenAI-compatible server (vLLM): default model kept and structured output.
 */
class VisionFieldExtractorHttpTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private volatile String answerContent;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = MAPPER.writeValueAsString(Map.of(
                    "id", "chatcmpl-1",
                    "object", "chat.completion",
                    "created", 1_700_000_000L,
                    "model", "vision-model",
                    "choices", List.of(Map.of(
                            "index", 0,
                            "finish_reason", "stop",
                            "message", Map.of("role", "assistant", "content", answerContent))),
                    "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2)));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void extract_sendsJsonSchemaResponseFormat_andKeepsDefaultModel() throws IOException {
        answerContent = """
                {"documentType":"invoice","fields":{"invoice.date":{"value":"12.03.2024",
                "evidence":"Datum: 12.03.2024","confidence":0.9}},"warnings":[]}""";
        VisionFieldExtractor extractor = extractor("json_schema");
        ExtractionRequest request = new ExtractionRequest(null, null, "doc-1", "de", Map.of(), null,
                List.of(new FieldDefinition("invoice.date", FieldType.DATE, "Invoice date", null)), List.of(), List.of());

        PageExtraction page = extractor.extract(pngImage(), 1, 1, request);

        JsonNode sent = MAPPER.readTree(requestBody.get());
        assertThat(sent.get("model").asString()).isEqualTo("vision-model");
        JsonNode responseFormat = sent.get("response_format");
        assertThat(responseFormat.get("type").asString()).isEqualTo("json_schema");
        JsonNode jsonSchema = responseFormat.get("json_schema");
        assertThat(jsonSchema.get("strict").asBoolean()).isTrue();
        assertThat(jsonSchema.get("schema").get("properties").get("fields").get("properties").has("invoice.date"))
                .isTrue();
        assertThat(page.fields().get("invoice.date").value()).isEqualTo("2024-03-12");
        assertThat(page.fields().get("invoice.date").confidence()).isEqualTo(0.9);
    }

    @Test
    void extract_jsonObjectMode_sendsJsonObjectAndSchemaInPrompt() throws IOException {
        answerContent = "{\"fields\":{}}";
        VisionFieldExtractor extractor = extractor("json_object");
        ExtractionRequest request = new ExtractionRequest(null, null, "doc-1", null, Map.of(), null,
                List.of(FieldDefinition.adHoc("name")), List.of(), List.of());

        extractor.extract(pngImage(), 1, 1, request);

        JsonNode sent = MAPPER.readTree(requestBody.get());
        assertThat(sent.get("model").asString()).isEqualTo("vision-model");
        assertThat(sent.get("response_format").get("type").asString()).isEqualTo("json_object");
        assertThat(sent.toString()).contains("JSON Schema");
    }

    private VisionFieldExtractor extractor(String responseFormat) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .apiKey("test-key")
                .model("vision-model")
                .maxRetries(0)
                .build();
        ChatClient client = ChatClient.create(OpenAiChatModel.builder().options(options).build());
        return new VisionFieldExtractor(client, new VisionImagePreprocessor(256, 0.75f), MAPPER,
                new ConfidenceCalculator(0.3, 0.5), responseFormat);
    }

    private static byte[] pngImage() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
}
