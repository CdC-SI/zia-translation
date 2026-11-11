package zas.admin.zia.translation.service.extraction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import zas.admin.zia.translation.service.image.VisionImagePreprocessor;

import java.util.*;

import static zas.admin.zia.translation.service.extraction.ExtractionSchemaGenerator.*;

/**
 * Reads the requested values on one page, or on the pages of the main document, with the internal vision model,
 * using structured output: the answer of the model is constrained by a JSON Schema generated from the requested
 * definitions. When several pages are sent in one call, each value carries the page it is read on.
 * <p>
 * The values of the answer are then normalized ({@link FieldValueNormalizer}) and their confidence is computed
 * ({@link ConfidenceCalculator}). Only the requested keys are read from the answer.
 */
@Component
class VisionFieldExtractor {

    private static final Logger log = LoggerFactory.getLogger(VisionFieldExtractor.class);

    static final String SYSTEM_PROMPT = """
            You extract data from a document. You receive the images of one or several pages of an uploaded file, \
            in the order given in the request.

            Rules:
            - Return only values that are actually printed on these images. Never guess, infer or invent a value. \
            If a value is not printed, set "value" and "evidence" to null and "confidence" to 0.
            - "evidence" is the exact text the value is read from, copied as printed (with its label when it \
            helps, e.g. "Rechnungsdatum: 12.03.2024").
            - "value" is the value written in the requested format.
            - "confidence" is your confidence, between 0 and 1, that the value is exactly the one printed.
            - When "page" is requested, it is the page of the file (as numbered in the request) the value is read on.
            - For each requested collection, return one item per row printed on these images (an empty array if \
            none). A row is returned once, even if it is repeated or continued on another page.
            - On a form with check boxes or with a list of printed items, only the items that are checked (cross, \
            tick, filled box) or filled in (quantity, amount or text written) are rows: never return an item that is \
            only printed. Give its code as printed, and describe the state of the box in "evidence" (e.g. "[x] \
            <code> <label>"). If the state of a box is uncertain, say it in "warnings".
            - "documentType" is a short free classification of the document (e.g. medical_invoice), null if unknown.
            - "warnings" lists anything a reviewer should know (unreadable area, cut page, ambiguous value, \
            uncertain check box...). Never report the page numbering of the file as a problem.
            - Values listed as already known are hints to understand the document: never copy them into another key.
            """;

    private final ChatClient visionClient;
    private final VisionImagePreprocessor imagePreprocessor;
    private final ObjectMapper objectMapper;
    private final ConfidenceCalculator confidenceCalculator;
    private final ResponseFormatMode responseFormatMode;

    /**
     * The images are prepared with their own resolution: reading small check boxes and amounts of a form needs a
     * higher resolution than the translation.
     */
    @Autowired
    VisionFieldExtractor(
            @Qualifier("visionChatClient") ChatClient visionClient,
            ObjectMapper objectMapper,
            ConfidenceCalculator confidenceCalculator,
            @Value("${zia.extraction.response-format:json_schema}") String responseFormat,
            @Value("${zia.extraction.vision.max-edge:1792}") int maxEdge,
            @Value("${zia.extraction.vision.jpeg-quality:0.9}") float jpegQuality) {
        this(visionClient, new VisionImagePreprocessor(maxEdge, jpegQuality), objectMapper, confidenceCalculator,
                responseFormat);
    }

    VisionFieldExtractor(
            ChatClient visionClient,
            VisionImagePreprocessor imagePreprocessor,
            ObjectMapper objectMapper,
            ConfidenceCalculator confidenceCalculator,
            String responseFormat) {
        this.visionClient = visionClient;
        this.imagePreprocessor = imagePreprocessor;
        this.objectMapper = objectMapper;
        this.confidenceCalculator = confidenceCalculator;
        this.responseFormatMode = ResponseFormatMode.parse(responseFormat);
    }

    /**
     * Extracts the values of one page, the file not being classified.
     *
     * @throws PageExtractionException when the model cannot be called or its answer is not a JSON object
     */
    PageExtraction extract(byte[] pageImage, int page, int pageCount, ExtractionRequest request) {
        return extract(List.of(new PageImage(page, pageImage)), pageCount, request, false);
    }

    /**
     * Extracts the values of the given pages in one call.
     *
     * @param mainDocument {@code true} when the pages are the main document of a classified file: the other pages
     *                     are supporting documents that are not sent
     * @throws PageExtractionException when the model cannot be called or its answer is not a JSON object
     */
    PageExtraction extract(List<PageImage> pageImages, int pageCount, ExtractionRequest request,
                           boolean mainDocument) {
        if (pageImages.isEmpty()) {
            throw new IllegalArgumentException("No page to extract");
        }
        List<Integer> pages = pageImages.stream().map(PageImage::page).toList();
        boolean withPage = pages.size() > 1;
        ObjectNode schema = ExtractionSchemaGenerator.generate(request.fields(), request.collections(), withPage);
        String schemaJson = objectMapper.writeValueAsString(schema);
        List<Media> media = pageImages.stream().map(image -> imagePreprocessor.toMedia(image.image())).toList();
        UserMessage message = UserMessage.builder()
                .text(buildUserPrompt(pages, pageCount, request, mainDocument,
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
            throw new PageExtractionException("Vision model call failed on " + describe(pages), ex);
        }
        return parse(answer, pages, request);
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

    static String buildUserPrompt(int page, int pageCount, ExtractionRequest request, String schemaJson) {
        return buildUserPrompt(List.of(page), pageCount, request, false, schemaJson);
    }

    /**
     * The guided decoding of the server constrains the answer but does not show the schema to the model: the
     * requested keys and their description are therefore listed in the prompt.
     *
     * @param schemaJson schema to include in the prompt ({@code json_object} mode), {@code null} otherwise
     */
    static String buildUserPrompt(List<Integer> pages, int pageCount, ExtractionRequest request,
                                  boolean mainDocument, String schemaJson) {
        StringBuilder prompt = new StringBuilder();
        appendPagesDescription(prompt, pages, pageCount,
                mainDocument ? request.pageClassificationSettings().mainDocument() : null);
        if (isPresent(request.language())) {
            prompt.append(" Expected language of the document: ").append(request.language().strip()).append('.');
        }
        if (isPresent(request.profileInstructions())) {
            prompt.append("\n\nAbout the document:\n").append(request.profileInstructions().strip()).append('\n');
        }
        String where = pages.size() > 1 ? "these pages" : "this page";
        if (!request.fields().isEmpty()) {
            prompt.append("\nExtract the following values (\"fields\") if they are printed on ").append(where)
                    .append(":\n");
            request.fields().forEach(field -> appendField(prompt, field.key(), field));
        }
        for (CollectionDefinition collection : request.collections()) {
            prompt.append("\nExtract all the rows of the collection \"").append(collection.key()).append('"');
            if (isPresent(collection.description())) {
                prompt.append(" (").append(collection.description().strip()).append(')');
            }
            prompt.append(" printed on ").append(where).append(" (\"collections\"), each row having the values:\n");
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

    /**
     * Describes the images sent with the numbering of the file, which is technical: the model must neither confuse
     * it with the numbering printed on the document nor report the difference.
     */
    /**
     * @param mainDocument description of the main document when the pages are the main document of a classified
     *                     file, {@code null} otherwise
     */
    private static void appendPagesDescription(StringBuilder prompt, List<Integer> pages, int pageCount,
                                               String mainDocument) {
        if (pages.size() == 1) {
            prompt.append("The image is page ").append(pages.getFirst());
        } else {
            prompt.append("The images are, in this order, pages ").append(joinPages(pages));
        }
        prompt.append(" of an uploaded file of ").append(pageCount).append(pageCount > 1 ? " pages." : " page.");
        if (mainDocument != null) {
            prompt.append(pages.size() > 1 ? " They are" : " It is")
                    .append(" the main document of the file, to extract: ").append(mainDocument).append('.');
            if (pages.size() < pageCount) {
                prompt.append(" The other pages of the file are supporting documents or blank pages; they are not ")
                        .append("shown and no value may come from them.");
            }
        }
        prompt.append(" This numbering of the file is technical and may differ from the numbering printed on the ")
                .append("document (e.g. \"Seite 1 von 2\"): never report it as a warning.");
        if (pages.size() > 1) {
            prompt.append(" For each value, \"page\" is the page of the file it is read on.");
        }
    }

    private static String joinPages(List<Integer> pages) {
        return String.join(", ", pages.stream().map(String::valueOf).toList());
    }

    private static String describe(List<Integer> pages) {
        return (pages.size() == 1 ? "page " : "pages ") + joinPages(pages);
    }

    private static void appendField(StringBuilder prompt, String key, FieldDefinition field) {
        prompt.append("- ").append(key).append(": ").append(field.promptDescription()).append('\n');
    }

    PageExtraction parse(String answer, int page, ExtractionRequest request) {
        return parse(answer, List.of(page), request);
    }

    /**
     * @param pages pages sent to the model; a value without valid {@code page} is attributed to the first one
     */
    PageExtraction parse(String answer, List<Integer> pages, ExtractionRequest request) {
        String where = describe(pages);
        if (answer == null || answer.isBlank()) {
            throw new PageExtractionException("Empty answer of the vision model on " + where, null);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(extractJsonObject(answer));
        } catch (JacksonException | IllegalArgumentException ex) {
            throw new PageExtractionException("Invalid JSON answer of the vision model on " + where, ex);
        }
        if (root == null || !root.isObject()) {
            throw new PageExtractionException("Invalid JSON answer of the vision model on " + where, null);
        }
        List<String> warnings = new ArrayList<>();
        Map<String, ExtractedField> fields = new LinkedHashMap<>();
        JsonNode fieldsNode = root.get(FIELDS);
        for (FieldDefinition field : request.fields()) {
            ExtractedField extracted = readField(field, child(fieldsNode, field.key()), pages, field.key(), warnings);
            if (extracted != null) {
                fields.put(field.key(), extracted);
            }
        }
        Map<String, List<Map<String, ExtractedField>>> collections = new LinkedHashMap<>();
        JsonNode collectionsNode = root.get(COLLECTIONS);
        for (CollectionDefinition collection : request.collections()) {
            collections.put(collection.key(),
                    readRows(collection, child(collectionsNode, collection.key()), pages, warnings));
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
        return new PageExtraction(pages.getFirst(), pages, textOrNull(root.get(DOCUMENT_TYPE)), fields, collections,
                warnings);
    }

    private List<Map<String, ExtractedField>> readRows(CollectionDefinition collection, JsonNode rowsNode,
                                                       List<Integer> pages, List<String> warnings) {
        List<Map<String, ExtractedField>> rows = new ArrayList<>();
        if (rowsNode == null || !rowsNode.isArray()) {
            return rows;
        }
        for (JsonNode rowNode : rowsNode) {
            Map<String, ExtractedField> row = new LinkedHashMap<>();
            for (FieldDefinition field : collection.fields()) {
                String label = collection.key() + "." + field.key();
                ExtractedField extracted = readField(field, child(rowNode, field.key()), pages, label, warnings);
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

    /** Reads {@code {value, evidence, confidence, page}} (or a bare value), normalized; {@code null} when absent. */
    private ExtractedField readField(FieldDefinition field, JsonNode node, List<Integer> pages, String label,
                                     List<String> warnings) {
        if (node == null || node.isNull()) {
            return null;
        }
        String rawValue;
        String evidence = null;
        Double modelConfidence = null;
        int page = pages.getFirst();
        if (node.isObject()) {
            rawValue = textOrNull(node.get(VALUE));
            evidence = textOrNull(node.get(EVIDENCE));
            JsonNode confidenceNode = node.get(CONFIDENCE);
            if (confidenceNode != null && confidenceNode.isNumber()) {
                modelConfidence = confidenceNode.asDouble();
            }
            JsonNode pageNode = node.get(PAGE);
            if (pageNode != null && pageNode.isIntegralNumber() && pages.contains(pageNode.asInt())) {
                page = pageNode.asInt();
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
