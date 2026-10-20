package zas.admin.zia.translation.service.invoice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import zas.admin.zia.translation.service.image.VisionImagePreprocessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the requested invoice values on one page with the internal vision model.
 */
@Component
class VisionInvoiceFieldExtractor {

    private static final Logger log = LoggerFactory.getLogger(VisionInvoiceFieldExtractor.class);

    static final String SYSTEM_PROMPT = """
            You extract data from scanned Swiss medical invoices (Sumex / generalInvoiceRequest 5.00) sent to the \
            Swiss disability insurance (IV/AI). You receive the image of ONE page of the invoice.

            Rules:
            - Return only values that are actually printed on this page. Never guess, infer or invent a value \
            (in particular never invent a GLN, ZSR, AHV/AVS number or IBAN). If a value is not on the page, omit it.
            - Keys are dotted: `<role>.<field>` for parties, roles being biller (issuer of the invoice), provider \
            (who provided the service), debitor (who must pay, usually the IV office), insurance, patient, guarantor \
            and creditor (who receives the payment).
            - Formats: dates as yyyy-MM-dd; amounts with `.` as decimal separator and no thousands separator \
            (e.g. 1234.50); GLN = 13 digits (starting with 76); ssn = Swiss AHV/AVS number, 13 digits starting with \
            756, without dots; ZSR = one letter followed by 6 digits; UID = CHE-123.456.789; sex = male or female; \
            country = ISO 3166 alpha-2 code; IBAN without spaces.
            - balance.amount is the total amount of the invoice, balance.amount_due the amount still to pay.
            - `confidence` is your confidence (0 to 1) that the value is exactly the one printed on the page.
            - Service lines (`services`) are the billed positions: date, tariffType (tariff code of the XSD, e.g. \
            007, 590, 999), code, name, quantity, unitPrice, amount, vatRate and confidence.

            Answer ONLY with a JSON object, without any comment or Markdown, of the form:
            {"documentType": "<free classification, e.g. hearing_aid_invoice>",
             "fields": {"<key>": {"value": "<value>", "confidence": <0..1>}},
             "services": [{"date": "yyyy-MM-dd", "tariffType": "...", "code": "...", "name": "...", "quantity": 1, \
            "unitPrice": 0.0, "amount": 0.0, "vatRate": 0.0, "confidence": 0.0}],
             "warnings": ["<anything the reviewer should know, e.g. unreadable area>"]}
            """;

    private final ChatClient visionClient;
    private final VisionImagePreprocessor imagePreprocessor;
    private final ObjectMapper objectMapper;

    VisionInvoiceFieldExtractor(
            @Qualifier("visionChatClient") ChatClient visionClient,
            VisionImagePreprocessor imagePreprocessor,
            ObjectMapper objectMapper) {
        this.visionClient = visionClient;
        this.imagePreprocessor = imagePreprocessor;
        this.objectMapper = objectMapper;
    }

    /**
     * @throws PageExtractionException when the model cannot be called or its answer is not the expected JSON
     */
    PageExtraction extract(byte[] pageImage, int page, int pageCount, ExtractionContext context,
                           Set<String> requestedFields, boolean servicesRequested) {
        Media media = imagePreprocessor.toMedia(pageImage);
        UserMessage message = UserMessage.builder()
                .text(buildUserPrompt(page, pageCount, context, requestedFields, servicesRequested))
                .media(media)
                .build();
        String answer;
        try {
            answer = visionClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .messages(message)
                    .call()
                    .content();
        } catch (RuntimeException ex) {
            throw new PageExtractionException("Vision model call failed on page " + page, ex);
        }
        return parse(answer, page);
    }

    static String buildUserPrompt(int page, int pageCount, ExtractionContext context,
                                  Set<String> requestedFields, boolean servicesRequested) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Page ").append(page).append(" of ").append(pageCount).append('.');
        if (context.language() != null && !context.language().isBlank()) {
            prompt.append(" Expected language of the document: ").append(context.language()).append('.');
        }
        if (requestedFields.isEmpty()) {
            prompt.append("\n\nDo not extract any key: return \"fields\": {}.\n");
        } else {
            prompt.append("\n\nExtract the following keys if they are printed on this page:\n");
            requestedFields.forEach(key -> prompt.append("- ").append(key).append('\n'));
        }
        if (servicesRequested) {
            prompt.append("\nAlso extract all the service lines (`services`) printed on this page.\n");
        } else {
            prompt.append("\nDo not extract service lines: return \"services\": [].\n");
        }
        if (!context.knownFields().isEmpty()) {
            prompt.append("""

                    Values already known for this invoice (hints only, do not return them, never copy them into \
                    another key):
                    """);
            context.knownFields().forEach((key, value) ->
                    prompt.append("- ").append(key).append(" = ").append(value).append('\n'));
        }
        return prompt.toString();
    }

    PageExtraction parse(String answer, int page) {
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
        Map<String, ExtractedField> fields = parseFields(root.get("fields"), page);
        List<ExtractedService> services = parseServices(root.get("services"), page, warnings);
        JsonNode warningsNode = root.get("warnings");
        if (warningsNode != null && warningsNode.isArray()) {
            warningsNode.forEach(warning -> {
                if (warning.isString() && !warning.asString().isBlank()) {
                    warnings.add(warning.asString().strip());
                }
            });
        }
        return new PageExtraction(page, textOrNull(root.get("documentType")), fields, services, warnings);
    }

    private static Map<String, ExtractedField> parseFields(JsonNode fieldsNode, int page) {
        Map<String, ExtractedField> fields = new LinkedHashMap<>();
        if (fieldsNode == null || !fieldsNode.isObject()) {
            return fields;
        }
        for (Map.Entry<String, JsonNode> entry : fieldsNode.properties()) {
            JsonNode node = entry.getValue();
            String value;
            Double confidence = null;
            if (node.isObject()) {
                value = textOrNull(node.get("value"));
                JsonNode confidenceNode = node.get("confidence");
                if (confidenceNode != null && confidenceNode.isNumber()) {
                    confidence = confidenceNode.asDouble();
                }
            } else {
                value = textOrNull(node);
            }
            if (value != null) {
                fields.put(entry.getKey(), new ExtractedField(value, confidence, page));
            }
        }
        return fields;
    }

    private List<ExtractedService> parseServices(JsonNode servicesNode, int page, List<String> warnings) {
        List<ExtractedService> services = new ArrayList<>();
        if (servicesNode == null || !servicesNode.isArray()) {
            return services;
        }
        for (JsonNode node : servicesNode) {
            try {
                services.add(objectMapper.treeToValue(node, ExtractedService.class));
            } catch (JacksonException | IllegalArgumentException ex) {
                log.debug("Service line ignored on page {}: {}", page, ex.getMessage());
                warnings.add("A service line could not be read");
            }
        }
        return services;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isObject() || node.isArray()) {
            return null;
        }
        String text = node.asString();
        return text == null || text.isBlank() ? null : text.strip();
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

    static final class PageExtractionException extends RuntimeException {
        PageExtractionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
