package zas.admin.zia.translation.service.extraction;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.json.JsonMapper;
import zas.admin.zia.translation.service.image.VisionImagePreprocessor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class VisionFieldExtractorTest {

    private static final FieldDefinition GLN =
            new FieldDefinition("biller.gln", FieldType.IDENTIFIER, "GLN, 13 digits", null);
    private static final FieldDefinition DATE = new FieldDefinition("invoice.date", FieldType.DATE, "Invoice date", null);
    private static final FieldDefinition SEX =
            new FieldDefinition("patient.sex", FieldType.ENUM, "Sex", List.of("male", "female"));
    private static final CollectionDefinition SERVICES = new CollectionDefinition("services", "Service lines",
            List.of(new FieldDefinition("code", FieldType.STRING, "Code", null),
                    new FieldDefinition("amount", FieldType.AMOUNT, "Amount", null)));
    private static final ExtractionRequest REQUEST = new ExtractionRequest("sumex-invoice", "About invoices.", "D_1",
            "de", Map.of("patient.ssn", "7568250251199"), "Look at the footer.",
            List.of(GLN, DATE, SEX), List.of(SERVICES), List.of());

    private final VisionFieldExtractor extractor = new VisionFieldExtractor(mock(ChatClient.class),
            mock(VisionImagePreprocessor.class), JsonMapper.builder().build(), new ConfidenceCalculator(0.3, 0.5),
            "json_schema");

    @Test
    void parse_normalizesValues_computesConfidence_andReadsOnlyRequestedKeys() {
        String answer = """
                ```json
                {"documentType": "medical_invoice",
                 "fields": {
                   "biller.gln": {"value": "7601 0001 5291 6", "evidence": "GLN 7601 0001 5291 6", "confidence": 0.95},
                   "invoice.date": {"value": "12.03.2024", "evidence": null, "confidence": 0.9},
                   "patient.sex": {"value": "Female", "evidence": "Frau", "confidence": 0.8},
                   "not.requested": {"value": "x", "evidence": "x", "confidence": 1}
                 },
                 "collections": {"services": [
                   {"code": {"value": "1001", "evidence": "1001", "confidence": 0.9},
                    "amount": {"value": "Fr. 120.-", "evidence": "120.-", "confidence": 0.9}},
                   {"code": {"value": null, "evidence": null, "confidence": 0},
                    "amount": {"value": null, "evidence": null, "confidence": 0}}
                 ]},
                 "warnings": ["stamp over the total", " "]}
                ```""";

        PageExtraction page = extractor.parse(answer, 2, REQUEST);

        assertThat(page.page()).isEqualTo(2);
        assertThat(page.documentType()).isEqualTo("medical_invoice");
        assertThat(page.fields()).containsOnlyKeys("biller.gln", "invoice.date", "patient.sex");
        assertThat(page.fields().get("biller.gln"))
                .isEqualTo(new ExtractedField("7601000152916", 0.95, 2, "GLN 7601 0001 5291 6"));
        assertThat(page.fields().get("invoice.date")).isEqualTo(new ExtractedField("2024-03-12", 0.3, 2, null));
        assertThat(page.fields().get("patient.sex").value()).isEqualTo("female");
        assertThat(page.collections().get("services")).hasSize(1);
        assertThat(page.collections().get("services").getFirst().get("amount").value()).isEqualTo("120");
        assertThat(page.warnings()).containsExactly("stamp over the total");
    }

    @Test
    void parse_invalidValues_areDroppedWithWarning() {
        String answer = """
                {"fields": {"invoice.date": {"value": "sometime", "evidence": "sometime", "confidence": 0.9},
                            "patient.sex": {"value": "unknown", "evidence": "?", "confidence": 0.9},
                            "biller.gln": "7601000152916"},
                 "collections": {"services": "not an array"}}""";

        PageExtraction page = extractor.parse(answer, 1, REQUEST);

        assertThat(page.fields()).containsOnlyKeys("biller.gln");
        assertThat(page.fields().get("biller.gln").confidence()).isEqualTo(0.0);
        assertThat(page.collections().get("services")).isEmpty();
        assertThat(page.warnings()).containsExactly(
                "Value of 'invoice.date' ignored: not a valid date",
                "Value of 'patient.sex' ignored: not a valid enum");
    }

    @Test
    void parse_invalidAnswer_throws() {
        assertThatThrownBy(() -> extractor.parse(" ", 1, REQUEST))
                .isInstanceOf(VisionFieldExtractor.PageExtractionException.class);
        assertThatThrownBy(() -> extractor.parse("no json", 1, REQUEST))
                .isInstanceOf(VisionFieldExtractor.PageExtractionException.class);
        assertThatThrownBy(() -> extractor.parse("{not: json}", 1, REQUEST))
                .isInstanceOf(VisionFieldExtractor.PageExtractionException.class);
    }

    @Test
    void buildUserPrompt_describesRequestedKeysAndContext() {
        String prompt = VisionFieldExtractor.buildUserPrompt(2, 3, REQUEST, null);

        assertThat(prompt)
                .startsWith("The image is page 2 of an uploaded file of 3 pages.")
                .contains("never report it as a warning")
                .doesNotContain("Page 2 of 3")
                .doesNotContain("main document")
                .doesNotContain("\"page\" is the page")
                .contains("Expected language of the document: de.")
                .contains("About invoices.")
                .contains("- biller.gln: GLN, 13 digits. Format: identifier")
                .contains("- patient.sex: Sex. Format: one of the allowed values [male, female]")
                .contains("collection \"services\" (Service lines)")
                .contains("- amount: Amount. Format: amount")
                .contains("- patient.ssn = 7568250251199")
                .contains("Additional instructions:\nLook at the footer.")
                .doesNotContain("JSON Schema");
        assertThat(VisionFieldExtractor.buildUserPrompt(1, 1, REQUEST, "{\"type\":\"object\"}"))
                .contains("JSON Schema:\n{\"type\":\"object\"}");
    }

    @Test
    void buildUserPrompt_mainDocument_describesPagesSentAndPagesNotShown() {
        String prompt = VisionFieldExtractor.buildUserPrompt(List.of(1, 2), 8, REQUEST, true, null);

        assertThat(prompt)
                .startsWith("The images are, in this order, pages 1, 2 of an uploaded file of 8 pages.")
                .contains("They are the main document of the file, to extract: the main document of the file.")
                .contains("The other pages of the file are supporting documents or blank pages")
                .contains("\"Seite 1 von 2\"): never report it as a warning.")
                .contains("For each value, \"page\" is the page of the file it is read on.")
                .contains("printed on these pages");
        assertThat(VisionFieldExtractor.buildUserPrompt(List.of(1), 1, REQUEST, true, null))
                .startsWith("The image is page 1 of an uploaded file of 1 page. It is the main document")
                .doesNotContain("other pages");
    }

    @Test
    void buildUserPrompt_mainDocument_usesDescriptionOfTheProfile() {
        ExtractionRequest request = new ExtractionRequest("sumex-invoice", null, null, null, Map.of(), null,
                List.of(GLN), List.of(), List.of(), List.of(),
                new PageClassificationSettings("the invoice addressed to the IV office", null));

        assertThat(VisionFieldExtractor.buildUserPrompt(List.of(1), 3, request, true, null))
                .contains("It is the main document of the file, to extract: the invoice addressed to the IV office.");
        assertThat(VisionFieldExtractor.buildUserPrompt(List.of(1), 3, request, false, null))
                .doesNotContain("main document");
    }

    @Test
    void systemPrompt_detailed_describesEvidenceConfidenceAndPage() {
        assertThat(VisionFieldExtractor.systemPrompt(false))
                .contains("Never guess, infer or invent a value")
                .contains("\"evidence\"", "\"confidence\"", "\"page\"", "\"documentType\"", "\"warnings\"")
                .contains("Never report the page numbering of the file");
    }

    @Test
    void systemPrompt_simple_asksOnlyForTheValues() {
        assertThat(VisionFieldExtractor.systemPrompt(true))
                .contains("Never guess, infer or invent a value")
                .contains("without evidence or comment")
                .doesNotContain("\"evidence\"", "\"confidence\"", "\"documentType\"", "\"warnings\"");
    }

    @Test
    void systemPrompt_containsNoDocumentSpecificRule() {
        assertThat(List.of(VisionFieldExtractor.systemPrompt(false), VisionFieldExtractor.systemPrompt(true)))
                .allSatisfy(prompt -> assertThat(prompt)
                        .doesNotContain("check box", "909", "tariff", "Rechnungsdatum", "medical_invoice"));
    }

    @Test
    void parse_severalPages_attributesEachValueToItsPage() {
        String answer = """
                {"fields": {
                   "biller.gln": {"value": "7601000152916", "evidence": "7601000152916", "confidence": 0.9, "page": 2},
                   "invoice.date": {"value": "12.03.2024", "evidence": "12.03.2024", "confidence": 0.9, "page": 7}
                 },
                 "collections": {"services": [
                   {"code": {"value": "909.04", "evidence": "[x] 909.04", "confidence": 0.9, "page": 1},
                    "amount": {"value": "200.00", "evidence": "200.00", "confidence": 0.9, "page": 2}}
                 ]}}""";

        PageExtraction extraction = extractor.parse(answer, List.of(1, 2), REQUEST);

        assertThat(extraction.page()).isEqualTo(1);
        assertThat(extraction.pages()).containsExactly(1, 2);
        assertThat(extraction.label()).isEqualTo("Pages 1, 2");
        assertThat(extraction.fields().get("biller.gln").page()).isEqualTo(2);
        assertThat(extraction.fields().get("invoice.date").page()).as("page not sent").isEqualTo(1);
        Map<String, ExtractedField> row = extraction.collections().get("services").getFirst();
        assertThat(row.get("code").page()).isEqualTo(1);
        assertThat(row.get("amount").page()).isEqualTo(2);
    }

    @Test
    void parse_requiredFieldMissing_usesFallbackWhenEnumProvidesUncertainty() {
        FieldDefinition required = new FieldDefinition("civil_status_detected", FieldType.ENUM, "Attestation?",
                List.of("true", "false", "UNCLEAR"), false);
        ExtractionRequest request = new ExtractionRequest("gaime-civil-status", null, null, null, Map.of(), null,
                List.of(required), List.of(), List.of());
        String answer = """
                {"fields": {"civil_status_detected": {"value": null, "evidence": null, "confidence": 0}}}""";

        PageExtraction page = extractor.parse(answer, 1, request);

        assertThat(page.fields()).containsOnlyKeys("civil_status_detected");
        assertThat(page.fields().get("civil_status_detected")).isEqualTo(new ExtractedField("UNCLEAR", 0.0, 1, null));
        assertThat(page.warnings()).containsExactly("Required value of 'civil_status_detected' missing on page 1");
    }

    @Test
    void parse_requiredFieldMissing_withoutUncertaintyValue_isDroppedWithWarning() {
        FieldDefinition required = new FieldDefinition("civil_status_detected", FieldType.ENUM, "Attestation?",
                List.of("true", "false"), false);
        ExtractionRequest request = new ExtractionRequest("gaime-civil-status", null, null, null, Map.of(), null,
                List.of(required), List.of(), List.of());
        String answer = """
                {"fields": {"civil_status_detected": {"value": null, "evidence": null, "confidence": 0}}}""";

        PageExtraction page = extractor.parse(answer, 1, request);

        assertThat(page.fields()).isEmpty();
        assertThat(page.warnings()).containsExactly("Required value of 'civil_status_detected' missing on page 1");
    }

    @Test
    void parse_simpleAnswer_readsBareValues_andFlagsMissingRequiredOnes() {
        FieldDefinition civilStatus = new FieldDefinition("civil_status_detected", FieldType.ENUM, "Attestation?",
                List.of("true", "false", "UNCLEAR"), false);
        FieldDefinition signed = new FieldDefinition("signature_present", FieldType.ENUM, "Signed?",
                List.of("true", "false", "UNCLEAR"), false);
        ExtractionRequest request = new ExtractionRequest("gaime-civil-status", null, null, null, Map.of(), null,
                List.of(civilStatus, signed), List.of(), List.of(), List.of(), null, true, true);
        String answer = """
                {"fields": {"civil_status_detected": "true", "signature_present": null}}""";

        PageExtraction page = extractor.parse(answer, 1, request);

        assertThat(page.fields()).containsOnlyKeys("civil_status_detected", "signature_present");
        assertThat(page.fields().get("civil_status_detected").value()).isEqualTo("true");
        assertThat(page.fields().get("signature_present")).isEqualTo(new ExtractedField("UNCLEAR", 0.0, 1, null));
        assertThat(page.warnings()).containsExactly("Required value of 'signature_present' missing on page 1");
    }

    @Test
    void responseFormatMode_isValidated() {
        assertThat(VisionFieldExtractor.ResponseFormatMode.parse("JSON_OBJECT"))
                .isEqualTo(VisionFieldExtractor.ResponseFormatMode.JSON_OBJECT);
        assertThat(VisionFieldExtractor.ResponseFormatMode.parse(null))
                .isEqualTo(VisionFieldExtractor.ResponseFormatMode.JSON_SCHEMA);
        assertThatThrownBy(() -> VisionFieldExtractor.ResponseFormatMode.parse("text"))
                .isInstanceOf(IllegalStateException.class);
    }
}
