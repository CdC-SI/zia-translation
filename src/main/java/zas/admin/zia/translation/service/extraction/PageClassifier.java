package zas.admin.zia.translation.service.extraction;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import zas.admin.zia.translation.service.image.VisionImagePreprocessor;

import java.util.Collection;
import java.util.Locale;

/**
 * Classifies one page of the submitted file with the vision model (light call, structured output): page of the main
 * document, of a supporting document, or other page. The prompt is generic, the domain is described by the profile.
 */
@Component
class PageClassifier {

    /**
     * Generic rules: the meaning of the roles and of the answered keys. What the main document and the supporting
     * documents are is described by the profile ({@link PageClassificationSettings}).
     */
    static final String SYSTEM_PROMPT = """
            You classify ONE page of an uploaded file. The file has one main document, the document whose values \
            are extracted, with all its pages (including its continuation, annex or signature pages). It may be \
            followed or preceded by supporting documents (attachments, copies of other documents, letters...) and \
            by blank pages.

            Answer:
            - "title": main title or heading printed on the page, null if none.
            - "recipient": name of whom the document is addressed to, null if not printed.
            - "formId": identifier printed on a form, null if the page is not part of a form.
            - "printedPageNumber" and "printedPageCount": page numbering printed on the page (e.g. "Seite 2 von 2" \
            gives 2 and 2), null if none.
            - "continuesPreviousPage": true only when the page obviously continues the previous page of the file \
            (same document, no new letterhead or recipient).
            - "documentRole": MAIN for a page of the main document, SUPPORTING for a page of a supporting document, \
            OTHER for a blank page, a separator or an unreadable page.
            - "documentType": generic type of the document the page belongs to.
            - "evidence": short text printed on the page that justifies the role (title, recipient...).
            - "confidence": your confidence, between 0 and 1, in "documentRole".
            The page numbering of the file is technical and may differ from the numbering printed on the page.
            """;

    static final String TITLE = "title";
    static final String RECIPIENT = "recipient";
    static final String FORM_ID = "formId";
    static final String PRINTED_PAGE_NUMBER = "printedPageNumber";
    static final String PRINTED_PAGE_COUNT = "printedPageCount";
    static final String CONTINUES_PREVIOUS_PAGE = "continuesPreviousPage";
    static final String DOCUMENT_ROLE = "documentRole";
    static final String DOCUMENT_TYPE = "documentType";
    static final String EVIDENCE = "evidence";
    static final String CONFIDENCE = "confidence";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final ChatClient visionClient;
    private final VisionImagePreprocessor imagePreprocessor;
    private final ObjectMapper objectMapper;
    private final boolean jsonSchema;
    private final String schemaJson;

    PageClassifier(
            @Qualifier("visionChatClient") ChatClient visionClient,
            VisionImagePreprocessor imagePreprocessor,
            ObjectMapper objectMapper,
            @Value("${zia.extraction.response-format:json_schema}") String responseFormat) {
        this.visionClient = visionClient;
        this.imagePreprocessor = imagePreprocessor;
        this.objectMapper = objectMapper;
        this.jsonSchema = VisionFieldExtractor.ResponseFormatMode.parse(responseFormat)
                == VisionFieldExtractor.ResponseFormatMode.JSON_SCHEMA;
        this.schemaJson = objectMapper.writeValueAsString(schema());
    }

    /**
     * @param knownPages classification of the other pages already known (hints, previous pages), given as context
     * @throws PageClassificationException when the model cannot be called or its answer is not a classification
     */
    ClassifiedPage classify(PageImage pageImage, int pageCount, ExtractionRequest request,
                            Collection<PageClassification> knownPages) {
        UserMessage message = UserMessage.builder()
                .text(buildUserPrompt(pageImage.page(), pageCount, request, knownPages, jsonSchema ? null : schemaJson))
                .media(imagePreprocessor.toMedia(pageImage.image()))
                .build();
        String answer;
        try {
            answer = visionClient.prompt()
                    .options(chatOptions())
                    .system(SYSTEM_PROMPT)
                    .messages(message)
                    .call()
                    .content();
        } catch (RuntimeException ex) {
            throw new PageClassificationException("Vision model call failed on page " + pageImage.page(), ex);
        }
        return parse(answer, pageImage.page());
    }

    private OpenAiChatOptions.Builder chatOptions() {
        OpenAiChatModel.ResponseFormat.Builder format = OpenAiChatModel.ResponseFormat.builder();
        if (jsonSchema) {
            format.type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA).jsonSchema(schemaJson).strict(true);
        } else {
            format.type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT);
        }
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder();
        options.responseFormat(format.build());
        return options;
    }

    static String buildUserPrompt(int page, int pageCount, ExtractionRequest request,
                                  Collection<PageClassification> knownPages, String schemaJson) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("The image is page ").append(page).append(" of an uploaded file of ").append(pageCount)
                .append(pageCount > 1 ? " pages." : " page.");
        if (request.language() != null && !request.language().isBlank()) {
            prompt.append(" Expected language of the document: ").append(request.language().strip()).append('.');
        }
        PageClassificationSettings settings = request.pageClassificationSettings();
        prompt.append("\n\nThe main document is ").append(settings.mainDocument()).append('.');
        if (!settings.instructions().isEmpty()) {
            prompt.append("\n\nAbout the files:\n").append(settings.instructions()).append('\n');
        } else {
            prompt.append('\n');
        }
        if (!knownPages.isEmpty()) {
            prompt.append("\nClassification of other pages of the file (context only):\n");
            knownPages.forEach(known -> {
                prompt.append("- page ").append(known.page()).append(": ").append(known.documentRole());
                if (known.documentType() != null) {
                    prompt.append(' ').append(known.documentType());
                }
                if (known.formId() != null) {
                    prompt.append(" (form ").append(known.formId()).append(')');
                }
                if (known.evidence() != null) {
                    prompt.append(" \"").append(known.evidence()).append('"');
                }
                prompt.append('\n');
            });
        }
        if (schemaJson != null) {
            prompt.append("\nAnswer ONLY with a JSON object, without any comment or Markdown, valid against this ")
                    .append("JSON Schema:\n").append(schemaJson).append('\n');
        }
        return prompt.toString();
    }

    ClassifiedPage parse(String answer, int page) {
        if (answer == null || answer.isBlank()) {
            throw new PageClassificationException("Empty classification of page " + page, null);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(VisionFieldExtractor.extractJsonObject(answer));
        } catch (JacksonException | IllegalArgumentException ex) {
            throw new PageClassificationException("Invalid classification of page " + page, ex);
        }
        DocumentRole role = root == null ? null : enumOrNull(DocumentRole.class, root.get(DOCUMENT_ROLE));
        if (role == null) {
            throw new PageClassificationException("No document role for page " + page, null);
        }
        String formId = textOrNull(root.get(FORM_ID));
        if (formId != null && formId.length() > PageClassification.MAX_FORM_ID_LENGTH) {
            formId = null;
        }
        Double confidence = null;
        JsonNode confidenceNode = root.get(CONFIDENCE);
        if (confidenceNode != null && confidenceNode.isNumber()) {
            confidence = Math.clamp(confidenceNode.asDouble(), 0.0, 1.0);
        }
        PageClassification classification = new PageClassification(page, role,
                enumOrNull(DocumentType.class, root.get(DOCUMENT_TYPE)), formId, confidence,
                textOrNull(root.get(EVIDENCE)));
        JsonNode continues = root.get(CONTINUES_PREVIOUS_PAGE);
        return new ClassifiedPage(classification, positiveOrNull(root.get(PRINTED_PAGE_NUMBER)),
                positiveOrNull(root.get(PRINTED_PAGE_COUNT)), continues != null && continues.isBoolean()
                && continues.asBoolean());
    }

    /** Strict schema: the descriptive keys come first so that the model reads the page before classifying it. */
    static ObjectNode schema() {
        ObjectNode properties = NODES.objectNode();
        properties.set(TITLE, nullable("string", "Main title or heading printed on the page"));
        properties.set(RECIPIENT, nullable("string", "Whom the document is addressed to"));
        properties.set(FORM_ID, nullable("string", "Identifier printed on a form, e.g. 300.001"));
        properties.set(PRINTED_PAGE_NUMBER, nullable("integer", "Page number printed on the page"));
        properties.set(PRINTED_PAGE_COUNT, nullable("integer", "Page count printed on the page"));
        properties.putObject(CONTINUES_PREVIOUS_PAGE).put("type", "boolean");
        properties.set(DOCUMENT_ROLE, enumSchema(DocumentRole.values(), false));
        properties.set(DOCUMENT_TYPE, enumSchema(DocumentType.values(), true));
        properties.set(EVIDENCE, nullable("string", "Text printed on the page that justifies the role"));
        ObjectNode confidence = properties.putObject(CONFIDENCE);
        confidence.put("type", "number");
        confidence.put("description", "Confidence between 0 and 1");
        ObjectNode schema = NODES.objectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        ArrayNode required = schema.putArray("required");
        properties.propertyNames().forEach(required::add);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode nullable(String type, String description) {
        ObjectNode schema = NODES.objectNode();
        schema.putArray("type").add(type).add("null");
        schema.put("description", description);
        return schema;
    }

    private static ObjectNode enumSchema(Enum<?>[] constants, boolean nullable) {
        ObjectNode schema = NODES.objectNode();
        if (nullable) {
            schema.putArray("type").add("string").add("null");
        } else {
            schema.put("type", "string");
        }
        ArrayNode values = schema.putArray("enum");
        for (Enum<?> constant : constants) {
            values.add(constant.name());
        }
        if (nullable) {
            values.addNull();
        }
        return schema;
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, JsonNode node) {
        String text = textOrNull(node);
        if (text == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, text.toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Integer positiveOrNull(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.asInt() > 0 ? node.asInt() : null;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isObject() || node.isArray()) {
            return null;
        }
        String text = node.asString();
        return text == null || text.isBlank() ? null : text.strip();
    }

    static final class PageClassificationException extends RuntimeException {
        PageClassificationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
