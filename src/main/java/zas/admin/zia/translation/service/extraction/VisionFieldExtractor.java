package zas.admin.zia.translation.service.extraction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import zas.admin.zia.translation.service.image.VisionImagePreprocessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static zas.admin.zia.translation.service.extraction.ExtractionSchemaGenerator.COLLECTIONS;
import static zas.admin.zia.translation.service.extraction.ExtractionSchemaGenerator.CONFIDENCE;
import static zas.admin.zia.translation.service.extraction.ExtractionSchemaGenerator.DOCUMENT_TYPE;
import static zas.admin.zia.translation.service.extraction.ExtractionSchemaGenerator.EVIDENCE;
import static zas.admin.zia.translation.service.extraction.ExtractionSchemaGenerator.FIELDS;
import static zas.admin.zia.translation.service.extraction.ExtractionSchemaGenerator.VALUE;
import static zas.admin.zia.translation.service.extraction.ExtractionSchemaGenerator.WARNINGS;

/**
 * Reads the requested values on one page with the internal vision model, using structured output: the answer of the
 * model is constrained by a JSON Schema generated from the requested definitions.
 * <p>
 * The values of the answer are then normalized ({@link FieldValueNormalizer}) and their confidence is computed
 * ({@link ConfidenceCalculator}). Only the requested keys are read from the answer.
 */
@Component
class VisionFieldExtractor {

    private static final Logger log = LoggerFactory.getLogger(VisionFieldExtractor.class);

    static final String SYSTEM_PROMPT = """
            You extract data from a document. You receive the image of ONE page of the document.

            Rules:
            - Return only values that are actually printed on this page. Never guess, infer or invent a value. \
            If a value is not on this page, set "value" and "evidence" to null and "confidence" to 0.
            - "evidence" is the exact text of the page the value is read from, copied as printed (with its label \
            when it helps, e.g. "Rechnungsdatum: 12.03.2024").
            - "value" is the value written in the requested format.
            - "confidence" is your confidence, between 0 and 1, that the value is exactly the one printed on the page.
            - For each requested collection, return one item per row printed on this page (an empty array if none).
            - "documentType" is a short free classification of the document (e.g. medical_invoice), null if unknown.
            - "warnings" lists anything a reviewer should know (unreadable area, cut page, ambiguous value...).
            - Values listed as already known are hints to understand the document: never copy them into another key.
            """;

    private final ChatClient visionClient;
    private final VisionImagePreprocessor imagePreprocessor;
    private final ObjectMapper objectMapper;
    private final ConfidenceCalculator confidenceCalculator;
    private final ResponseFormatMode responseFormatMode;

    VisionFieldExtractor(
            @Qualifier("visionChatClient") ChatClient visionClient,
            VisionImagePreprocessor imagePreprocessor,
            ObjectMapper objectMapper,
            ConfidenceCalculator confidenceCalculator,
            @Value("${zia.extraction.response-format:json_schema}") String responseFormat) {
        this.visionClient = visionClient;
        this.imagePreprocessor = imagePreprocessor;
        this.objectMapper = objectMapper;
        this.confidenceCalculator = confidenceCalculator;
        this.responseFormatMode = ResponseFormatMode.parse(responseFormat);
    }

    /**
     * @throws PageExtractionException when the model cannot be called or its answer is not a JSON object
     */
    PageExtraction extract(byte[] pageImage, int page, int pageCount, ExtractionRequest request) {
        ObjectNode schema = ExtractionSchemaGenerator.generate(request.fields(), request.collections());
        String schemaJson = objectMapper.writeValueAsString(schema);
        Media media = imagePreprocessor.toMedia(pageImage);
        UserMessage message = UserMessage.builder()
                .text(buildUserPrompt(page, pageCount, request,
                        responseFormatMode == ResponseFormatMode.JSON_OBJECT ? schemaJson : null))
                .media(media)
                .build();
        String answer;
        try {
            answer = visionClient.prompt()
                    .options(chatOptions(schemaJson))
                    .system(SYSTEM_PROMPT)
                    .messages(message)
                    .call()
                    .content();
        } catch (RuntimeException ex) {
            throw new PageExtractionException("Vision model call failed on page " + page, ex);
        }
        return parse(answer, page, request);
    }

    private OpenAiChatOptions.Builder chatOptions(String schemaJson) {
        OpenAiChatModel.ResponseFormat.Builder format = OpenAiChatModel.ResponseFormat.builder();
        if (responseFormatMode == ResponseFormatMode.JSON_SCHEMA) {
            format.type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA).jsonSchema(schemaJson).strict(true);
        } else {
            format.type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT);
        }
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder();
        options.responseFormat(format.build());
        return options;
    }

    /**
     * The guided decoding of the server constrains the answer but does not show the schema to the model: the
     * requested keys and their description are therefore listed in the prompt.
     *
     * @param schemaJson schema to include in the prompt ({@code json_object} mode), {@code null} otherwise
     */
    static String buildUserPrompt(int page, int pageCount, ExtractionRequest request, String schemaJson) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Page ").append(page).append(" of ").append(pageCount).append('.');
        if (isPresent(request.language())) {
            prompt.append(" Expected language of the document: ").append(request.language().strip()).append('.');
        }
        if (isPresent(request.profileInstructions())) {
            prompt.append("\n\nAbout the document:\n").append(request.profileInstructions().strip()).append('\n');
        }
        if (!request.fields().isEmpty()) {
            prompt.append("\nExtract the following values (\"fields\") if they are printed on this page:\n");
            request.fields().forEach(field -> appendField(prompt, field.key(), field));
        }
        for (CollectionDefinition collection : request.collections()) {
            prompt.append("\nExtract all the rows of the collection \"").append(collection.key()).append('"');
            if (isPresent(collection.description())) {
                prompt.append(" (").append(collection.description().strip()).append(')');
            }
            prompt.append(" printed on this page (\"collections\"), each row having the values:\n");
            collection.fields().forEach(field -> appendField(prompt, field.key(), field));
        }
        if (!request.knownFields().isEmpty()) {
            prompt.append("\nValues already known (hints only, do not return them):\n");
            request.knownFields().forEach((key, value) ->
                    prompt.append("- ").append(key).append(" = ").append(value).append('\n'));
        }
        if (isPresent(request.instructions())) {
            prompt.append("\nAdditional instructions:\n").append(request.instructions().strip()).append('\n');
        }
        if (schemaJson != null) {
            prompt.append("\nAnswer ONLY with a JSON object, without any comment or Markdown, valid against this ")
                    .append("JSON Schema:\n").append(schemaJson).append('\n');
        }
        return prompt.toString();
    }

    private static void appendField(StringBuilder prompt, String key, FieldDefinition field) {
        prompt.append("- ").append(key).append(": ").append(field.promptDescription()).append('\n');
    }

    PageExtraction parse(String answer, int page, ExtractionRequest request) {
        if (answer == null || answer.isBlank()) {
            throw new PageExtractionException("Empty answer of the vision model on page " + page, null);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(extractJsonObject(answer));
        } catch (JacksonException | IllegalArgumentException ex) {
            throw new PageExtractionException("Invalid JSON answer of the vision model on page " + page, ex);
        }
        if (root == null || !root.isObject()) {
            throw new PageExtractionException("Invalid JSON answer of the vision model on page " + page, null);
        }
        List<String> warnings = new ArrayList<>();
        Map<String, ExtractedField> fields = new LinkedHashMap<>();
        JsonNode fieldsNode = root.get(FIELDS);
        for (FieldDefinition field : request.fields()) {
            ExtractedField extracted = readField(field, child(fieldsNode, field.key()), page, field.key(), warnings);
            if (extracted != null) {
                fields.put(field.key(), extracted);
            }
        }
        Map<String, List<Map<String, ExtractedField>>> collections = new LinkedHashMap<>();
        JsonNode collectionsNode = root.get(COLLECTIONS);
        for (CollectionDefinition collection : request.collections()) {
            collections.put(collection.key(), readRows(collection, child(collectionsNode, collection.key()), page, warnings));
        }
        JsonNode warningsNode = root.get(WARNINGS);
        if (warningsNode != null && warningsNode.isArray()) {
            warningsNode.forEach(warning -> {
                String text = textOrNull(warning);
                if (text != null) {
                    warnings.add(text);
                }
            });
        }
        return new PageExtraction(page, textOrNull(root.get(DOCUMENT_TYPE)), fields, collections, warnings);
    }

    private List<Map<String, ExtractedField>> readRows(CollectionDefinition collection, JsonNode rowsNode, int page,
                                                       List<String> warnings) {
        List<Map<String, ExtractedField>> rows = new ArrayList<>();
        if (rowsNode == null || !rowsNode.isArray()) {
            return rows;
        }
        for (JsonNode rowNode : rowsNode) {
            Map<String, ExtractedField> row = new LinkedHashMap<>();
            for (FieldDefinition field : collection.fields()) {
                String label = collection.key() + "." + field.key();
                ExtractedField extracted = readField(field, child(rowNode, field.key()), page, label, warnings);
                if (extracted != null) {
                    row.put(field.key(), extracted);
                }
            }
            if (!row.isEmpty()) {
                rows.add(row);
            }
        }
        return rows;
    }

    /** Reads {@code {value, evidence, confidence}} (or a bare value), normalized; {@code null} when absent. */
    private ExtractedField readField(FieldDefinition field, JsonNode node, int page, String label,
                                     List<String> warnings) {
        if (node == null || node.isNull()) {
            return null;
        }
        String rawValue;
        String evidence = null;
        Double modelConfidence = null;
        if (node.isObject()) {
            rawValue = textOrNull(node.get(VALUE));
            evidence = textOrNull(node.get(EVIDENCE));
            JsonNode confidenceNode = node.get(CONFIDENCE);
            if (confidenceNode != null && confidenceNode.isNumber()) {
                modelConfidence = confidenceNode.asDouble();
            }
        } else {
            rawValue = textOrNull(node);
        }
        if (rawValue == null) {
            return null;
        }
        String value = FieldValueNormalizer.normalize(field, rawValue);
        if (value == null) {
            log.debug("Value of '{}' rejected on page {}: unexpected format for {}", label, page, field.type());
            warnings.add("Value of '%s' ignored: not a valid %s".formatted(label, field.type().name().toLowerCase(Locale.ROOT)));
            return null;
        }
        double confidence = confidenceCalculator.compute(field, value, evidence, modelConfidence);
        return new ExtractedField(value, confidence, page, evidence);
    }

    private static JsonNode child(JsonNode parent, String name) {
        return parent != null && parent.isObject() ? parent.get(name) : null;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isObject() || node.isArray()) {
            return null;
        }
        String text = node.asString();
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static boolean isPresent(String text) {
        return text != null && !text.isBlank();
    }

    /** Strips Markdown fences or text around the JSON object answered by the model. */
    static String extractJsonObject(String answer) {
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("No JSON object in the answer");
        }
        return answer.substring(start, end + 1);
    }

    enum ResponseFormatMode {
        JSON_SCHEMA,
        JSON_OBJECT;

        static ResponseFormatMode parse(String value) {
            return switch (value == null ? "" : value.strip().toLowerCase(Locale.ROOT)) {
                case "json_schema", "" -> JSON_SCHEMA;
                case "json_object" -> JSON_OBJECT;
                default -> throw new IllegalStateException(
                        "Invalid zia.extraction.response-format '%s' (json_schema or json_object)".formatted(value));
            };
        }
    }

    static final class PageExtractionException extends RuntimeException {
        PageExtractionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
