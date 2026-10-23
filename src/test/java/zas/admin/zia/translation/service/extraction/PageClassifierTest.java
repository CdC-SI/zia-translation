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
import tools.jackson.databind.node.ObjectNode;
import zas.admin.zia.translation.service.image.VisionImagePreprocessor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageClassifierTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final ExtractionRequest REQUEST = new ExtractionRequest("sumex-invoice", null, "D_1", "de",
            Map.of(), null, List.of(FieldDefinition.adHoc("a")), List.of(), List.of(), List.of(),
            new PageClassificationSettings("the invoice addressed to the IV office", "IV dossiers: form 300.001."));

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private volatile String answerContent;
    private volatile int status = 200;

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
            exchange.sendResponseHeaders(status, bytes.length);
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
    void classify_sendsStrictSchemaAndContext_andParsesAnswer() throws IOException {
        answerContent = """
                {"title": "Rechnung für Hörgeräteversorgung", "recipient": "IV-Stelle Bern", "formId": "300.001",
                 "printedPageNumber": 2, "printedPageCount": 2, "continuesPreviousPage": true,
                 "documentRole": "MAIN", "documentType": "FORM", "evidence": "Seite 2 von 2", "confidence": 0.9}""";

        ClassifiedPage result = classifier("json_schema").classify(new PageImage(2, pngImage()), 8, REQUEST,
                List.of(new PageClassification(1, DocumentRole.MAIN, DocumentType.FORM, "300.001", null, null)));

        assertThat(result.classification()).isEqualTo(new PageClassification(2, DocumentRole.MAIN, DocumentType.FORM,
                "300.001", 0.9, "Seite 2 von 2"));
        assertThat(result.printedPageNumber()).isEqualTo(2);
        assertThat(result.printedPageCount()).isEqualTo(2);
        assertThat(result.continuesPreviousPage()).isTrue();

        JsonNode sent = MAPPER.readTree(requestBody.get());
        JsonNode schema = sent.get("response_format").get("json_schema").get("schema");
        assertThat(schema.get("properties").get("documentRole").get("enum").toString())
                .isEqualTo("[\"MAIN\",\"SUPPORTING\",\"OTHER\"]");
        assertThat(schema.get("properties").get("documentType").get("enum").toString()).contains("POWER_OF_ATTORNEY");
        JsonNode messages = sent.get("messages");
        String text = messages.get(messages.size() - 1).get("content").get(0).get("text").asString();
        assertThat(text)
                .startsWith("The image is page 2 of an uploaded file of 8 pages.")
                .contains("The main document is the invoice addressed to the IV office.")
                .contains("About the files:\nIV dossiers: form 300.001.")
                .contains("- page 1: MAIN FORM (form 300.001)");
    }

    @Test
    void classify_modelFailure_throws() throws IOException {
        status = 500;
        answerContent = "{}";
        PageClassifier classifier = classifier("json_schema");
        PageImage page = new PageImage(1, pngImage());

        assertThatThrownBy(() -> classifier.classify(page, 1, REQUEST, List.of()))
                .isInstanceOf(PageClassifier.PageClassificationException.class);
    }

    @Test
    void parse_toleratesUnknownValues_andRejectsMissingRole() {
        PageClassifier classifier = classifier("json_object");

        ClassifiedPage page = classifier.parse("""
                ```json
                {"documentRole": "supporting", "documentType": "letter", "formId": "%s",
                 "printedPageNumber": 0, "continuesPreviousPage": "yes", "confidence": 3}
                ```""".formatted("x".repeat(36)), 4);

        assertThat(page.classification()).isEqualTo(
                new PageClassification(4, DocumentRole.SUPPORTING, null, null, 1.0, null));
        assertThat(page.printedPageNumber()).isNull();
        assertThat(page.continuesPreviousPage()).isFalse();
        assertThatThrownBy(() -> classifier.parse("{\"documentRole\": \"INVOICE\"}", 1))
                .isInstanceOf(PageClassifier.PageClassificationException.class);
        assertThatThrownBy(() -> classifier.parse("no json", 1))
                .isInstanceOf(PageClassifier.PageClassificationException.class);
        assertThatThrownBy(() -> classifier.parse(" ", 1))
                .isInstanceOf(PageClassifier.PageClassificationException.class);
    }

    @Test
    void prompts_areGeneric_withoutProfile() {
        ExtractionRequest adHoc = new ExtractionRequest(null, null, null, null, Map.of(), null,
                List.of(FieldDefinition.adHoc("a")), List.of(), List.of(),
                List.of(new PageClassification(1, DocumentRole.MAIN, null, null, null, null)), null);

        String prompt = PageClassifier.buildUserPrompt(2, 2, adHoc, List.of(), null);

        assertThat(prompt).contains("The main document is the main document of the file.")
                .doesNotContain("About the files");
        assertThat(PageClassifier.SYSTEM_PROMPT.toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("invoice").doesNotContain("insurance");
    }

    @Test
    void buildUserPrompt_jsonObjectMode_includesSchema() {
        String prompt = PageClassifier.buildUserPrompt(1, 1, REQUEST, List.of(), "{\"type\":\"object\"}");

        assertThat(prompt).startsWith("The image is page 1 of an uploaded file of 1 page.")
                .doesNotContain("Classification of other pages")
                .contains("JSON Schema:\n{\"type\":\"object\"}");
    }

    @Test
    void schema_isStrict_withDescriptiveKeysFirst() {
        ObjectNode schema = PageClassifier.schema();

        List<String> names = new ArrayList<>(schema.get("properties").propertyNames());
        assertThat(names).containsExactly("title", "recipient", "formId", "printedPageNumber", "printedPageCount",
                "continuesPreviousPage", "documentRole", "documentType", "evidence", "confidence");
        assertThat(schema.get("required")).hasSize(names.size());
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
    }

    private PageClassifier classifier(String responseFormat) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .apiKey("test-key")
                .model("vision-model")
                .maxRetries(0)
                .build();
        ChatClient client = ChatClient.create(OpenAiChatModel.builder().options(options).build());
        return new PageClassifier(client, new VisionImagePreprocessor(256, 0.75f), MAPPER, responseFormat);
    }

    private static byte[] pngImage() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
}
