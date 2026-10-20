package zas.admin.zia.translation.service.invoice;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionResultMergerTest {

    @Test
    void merge_keepsHighestConfidenceAcrossPages() {
        PageExtraction page1 = page(1, Map.of("biller.gln", new ExtractedField("7601000000001", 0.5, 1)));
        PageExtraction page2 = page(2, Map.of("biller.gln", new ExtractedField("7601000152916", 0.93, 2)));

        ExtractionResponse response = ExtractionResultMerger.merge(
                List.of(page1, page2), Set.of("biller.gln"), false, List.of());

        assertThat(response.fields()).containsExactly(
                Map.entry("biller.gln", new ExtractedField("7601000152916", 0.93, 2)));
    }

    @Test
    void merge_ignoresKeysNotRequested() {
        PageExtraction page = page(1, Map.of(
                "biller.gln", new ExtractedField("7601000152916", 0.9, 1),
                "patient.familyname", new ExtractedField("Muster", 0.9, 1),
                "unknown.key", new ExtractedField("x", 0.9, 1)));

        ExtractionResponse response = ExtractionResultMerger.merge(
                List.of(page), Set.of("biller.gln"), false, List.of());

        assertThat(response.fields()).containsOnlyKeys("biller.gln");
    }

    @Test
    void merge_clampsConfidenceAndDefaultsMissingToZero() {
        PageExtraction page = page(1, Map.of(
                "biller.gln", new ExtractedField("7601000152916", 1.7, 1),
                "biller.zsr", new ExtractedField("A123456", null, 1),
                "biller.city", new ExtractedField("Bern", -0.2, 1)));

        ExtractionResponse response = ExtractionResultMerger.merge(
                List.of(page), Set.of("biller.gln", "biller.zsr", "biller.city"), false, List.of());

        assertThat(response.fields().get("biller.gln").confidence()).isEqualTo(1.0);
        assertThat(response.fields().get("biller.zsr").confidence()).isEqualTo(0.0);
        assertThat(response.fields().get("biller.city").confidence()).isEqualTo(0.0);
    }

    @Test
    void merge_normalizesDatesAmountsAndIdentifiers() {
        PageExtraction page = page(1, Map.of(
                "treatment.date_begin", new ExtractedField("26.08.2026", 0.8, 1),
                "patient.birthdate", new ExtractedField("1950-01-31", 0.8, 1),
                "balance.amount", new ExtractedField("CHF 1'234,50", 0.8, 1),
                "balance.amount_due", new ExtractedField("120.-", 0.8, 1),
                "patient.ssn", new ExtractedField("756.8250.2511.99", 0.8, 1),
                "esr.iban", new ExtractedField("ch93 0076 2011 6238 5295 7", 0.8, 1)));

        ExtractionResponse response = ExtractionResultMerger.merge(List.of(page),
                Set.of("treatment.date_begin", "patient.birthdate", "balance.amount", "balance.amount_due",
                        "patient.ssn", "esr.iban"),
                false, List.of());

        assertThat(response.fields().get("treatment.date_begin").value()).isEqualTo("2026-08-26");
        assertThat(response.fields().get("patient.birthdate").value()).isEqualTo("1950-01-31");
        assertThat(response.fields().get("balance.amount").value()).isEqualTo("1234.50");
        assertThat(response.fields().get("balance.amount_due").value()).isEqualTo("120");
        assertThat(response.fields().get("patient.ssn").value()).isEqualTo("7568250251199");
        assertThat(response.fields().get("esr.iban").value()).isEqualTo("CH9300762011623852957");
    }

    @Test
    void merge_dropsInvalidDatesAmountsAndBlankValues() {
        PageExtraction page = page(1, Map.of(
                "treatment.date_begin", new ExtractedField("31.02.2026", 0.9, 1),
                "balance.amount", new ExtractedField("about hundred", 0.9, 1),
                "biller.city", new ExtractedField("  ", 0.9, 1)));

        ExtractionResponse response = ExtractionResultMerger.merge(List.of(page),
                Set.of("treatment.date_begin", "balance.amount", "biller.city"), false, List.of());

        assertThat(response.fields()).isEmpty();
    }

    @Test
    void merge_invalidValueOnBestPage_keepsValidValueOfOtherPage() {
        PageExtraction page1 = page(1, Map.of("treatment.date_begin", new ExtractedField("not a date", 0.99, 1)));
        PageExtraction page2 = page(2, Map.of("treatment.date_begin", new ExtractedField("2026-08-26", 0.7, 2)));

        ExtractionResponse response = ExtractionResultMerger.merge(
                List.of(page1, page2), Set.of("treatment.date_begin"), false, List.of());

        assertThat(response.fields().get("treatment.date_begin"))
                .isEqualTo(new ExtractedField("2026-08-26", 0.7, 2));
    }

    @Test
    void merge_servicesRequested_concatenatesAndDeduplicatesLines() {
        ExtractedService line1 = new ExtractedService("26.08.2026", "590", "1001", "Fitting", 1.0, 100.0, 100.0, 0.0, 0.9);
        ExtractedService line2 = new ExtractedService("2026-08-27", "590", "1002", "Control", 1.0, 50.0, 50.0, 0.0, 1.4);
        ExtractedService duplicate = new ExtractedService("2026-08-26", "590", "1001", "Fitting", 1.0, 100.0, 100.0, 0.0, 0.8);

        ExtractionResponse response = ExtractionResultMerger.merge(List.of(
                        new PageExtraction(1, null, Map.of(), List.of(line1), List.of()),
                        new PageExtraction(2, null, Map.of(), List.of(line2, duplicate), List.of())),
                Set.of(), true, List.of());

        assertThat(response.services()).hasSize(2);
        assertThat(response.services().get(0).date()).isEqualTo("2026-08-26");
        assertThat(response.services().get(1).confidence()).isEqualTo(1.0);
    }

    @Test
    void merge_servicesNotRequested_returnsNoService() {
        ExtractedService line = new ExtractedService("2026-08-26", "590", "1001", "Fitting", 1.0, 100.0, 100.0, 0.0, 0.9);

        ExtractionResponse response = ExtractionResultMerger.merge(
                List.of(new PageExtraction(1, null, Map.of(), List.of(line), List.of())), Set.of(), false, List.of());

        assertThat(response.services()).isEmpty();
    }

    @Test
    void merge_collectsDocumentTypeAndWarnings() {
        ExtractionResponse response = ExtractionResultMerger.merge(List.of(
                        new PageExtraction(1, null, Map.of(), List.of(), List.of("stamp over the total")),
                        new PageExtraction(2, "hearing_aid_invoice", Map.of(), List.of(), List.of()),
                        new PageExtraction(3, "other", Map.of(), List.of(), List.of())),
                Set.of(), false, List.of("Page 4 could not be analysed by the OCR service"));

        assertThat(response.documentType()).isEqualTo("hearing_aid_invoice");
        assertThat(response.warnings()).containsExactly(
                "Page 4 could not be analysed by the OCR service", "Page 1: stamp over the total");
    }

    private static PageExtraction page(int page, Map<String, ExtractedField> fields) {
        return new PageExtraction(page, null, fields, List.of(), List.of());
    }
}
