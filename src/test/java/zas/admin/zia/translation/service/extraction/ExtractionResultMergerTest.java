package zas.admin.zia.translation.service.extraction;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionResultMergerTest {

    private static final FieldDefinition GLN = new FieldDefinition("biller.gln", FieldType.IDENTIFIER, null, null);
    private static final FieldDefinition DATE = new FieldDefinition("invoice.date", FieldType.DATE, null, null);
    private static final CollectionDefinition SERVICES = new CollectionDefinition("services", null,
            List.of(new FieldDefinition("code", FieldType.STRING, null, null),
                    new FieldDefinition("amount", FieldType.AMOUNT, null, null)));
    private static final ExtractionRequest REQUEST = new ExtractionRequest("sumex-invoice", null, "D_1", null,
            Map.of(), null, List.of(GLN, DATE), List.of(SERVICES), List.of("Keys ignored: x"));

    @Test
    void merge_keepsMostConfidentValue_andFirstPageOnTie() {
        PageExtraction page1 = page(1, "invoice", Map.of(
                "biller.gln", field("7601000152916", 0.6, 1),
                "invoice.date", field("2024-03-12", 0.8, 1)), Map.of(), List.of());
        PageExtraction page2 = page(2, "other", Map.of(
                "biller.gln", field("7601000152923", 0.9, 2),
                "invoice.date", field("2024-03-13", 0.8, 2)), Map.of(), List.of("stamp over the total"));

        ExtractionResponse response = ExtractionResultMerger.merge(REQUEST, List.of(page1, page2), List.of("Keys ignored: x"));

        assertThat(response.profile()).isEqualTo("sumex-invoice");
        assertThat(response.documentType()).isEqualTo("invoice");
        assertThat(response.fields().get("biller.gln").value()).isEqualTo("7601000152923");
        assertThat(response.fields().get("biller.gln").page()).isEqualTo(2);
        assertThat(response.fields().get("invoice.date").page()).isEqualTo(1);
        assertThat(response.collections()).containsOnlyKeys("services");
        assertThat(response.collections().get("services")).isEmpty();
        assertThat(response.warnings()).containsExactly("Keys ignored: x", "Page 2: stamp over the total");
    }

    @Test
    void merge_concatenatesRows_andDropsRowsRepeatedOnAnotherPage() {
        Map<String, ExtractedField> line1 = Map.of("code", field("1001", 0.9, 1), "amount", field("10.00", 0.9, 1));
        Map<String, ExtractedField> line1Again = Map.of("code", field("1001", 0.7, 2), "amount", field("10.00", 0.7, 2));
        Map<String, ExtractedField> line2 = Map.of("code", field("1002", 0.9, 2));
        PageExtraction page1 = page(1, null, Map.of(), Map.of("services", List.of(line1, line1)), List.of());
        PageExtraction page2 = page(2, null, Map.of(), Map.of("services", List.of(line1Again, Map.of(), line2)), List.of());

        ExtractionResponse response = ExtractionResultMerger.merge(REQUEST, List.of(page1, page2), List.of());

        assertThat(response.collections().get("services")).containsExactly(line1, line1, line2);
        assertThat(response.fields()).isEmpty();
        assertThat(response.documentType()).isNull();
    }

    @Test
    void merge_withoutCollections_omitsCollections() {
        ExtractionRequest request = new ExtractionRequest(null, null, null, null, Map.of(), null,
                List.of(GLN), List.of(), List.of());

        ExtractionResponse response = ExtractionResultMerger.merge(request,
                List.of(page(1, null, Map.of(), Map.of(), List.of())), List.of());

        assertThat(response.profile()).isNull();
        assertThat(response.collections()).isNull();
    }

    private static PageExtraction page(int page, String type, Map<String, ExtractedField> fields,
                                       Map<String, List<Map<String, ExtractedField>>> collections, List<String> warnings) {
        return new PageExtraction(page, type, fields, collections, warnings);
    }

    private static ExtractedField field(String value, double confidence, int page) {
        return new ExtractedField(value, confidence, page, value);
    }
}
