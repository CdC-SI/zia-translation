package zas.admin.zia.translation.service.extraction;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static zas.admin.zia.translation.service.extraction.DocumentRole.*;

class PageClassificationResolverTest {

    @Test
    void ivDossier_extractsOnlyTheFormPages() {
        // D_0031: IV form 300.001 (2 pages), 2 seller invoices, 2 manufacturer invoices of 2 pages each
        Map<Integer, ClassifiedPage> classified = new HashMap<>();
        classified.put(1, model(1, MAIN, DocumentType.FORM, "300.001", 1, 2, false));
        classified.put(2, model(2, MAIN, DocumentType.FORM, "300.001", 2, 2, true));
        classified.put(3, model(3, SUPPORTING, DocumentType.INVOICE, null, null, null, false));
        classified.put(4, model(4, SUPPORTING, DocumentType.INVOICE, null, null, null, false));
        classified.put(5, model(5, SUPPORTING, DocumentType.INVOICE, null, 1, 2, false));
        classified.put(6, model(6, SUPPORTING, DocumentType.INVOICE, null, 2, 2, true));
        classified.put(7, model(7, SUPPORTING, DocumentType.INVOICE, null, 1, 2, false));
        classified.put(8, model(8, SUPPORTING, DocumentType.INVOICE, null, 2, 2, true));

        PageClassificationResolver.Resolution resolution = PageClassificationResolver.resolve(8, Map.of(), classified);

        assertThat(resolution.pagesToExtract()).containsExactly(1, 2);
        assertThat(resolution.pages()).hasSize(8);
        assertThat(resolution.pages()).extracting(PageClassification::documentRole)
                .containsExactly(MAIN, MAIN, SUPPORTING, SUPPORTING, SUPPORTING, SUPPORTING, SUPPORTING, SUPPORTING);
        assertThat(resolution.warnings()).isEmpty();
    }

    @Test
    void continuationOfTheMainDocument_isPromotedToMain() {
        Map<Integer, ClassifiedPage> classified = new HashMap<>();
        classified.put(1, model(1, MAIN, DocumentType.FORM, null, 1, 2, false));
        classified.put(2, model(2, SUPPORTING, DocumentType.OTHER, null, 2, 2, false));
        classified.put(3, model(3, OTHER, null, "300.001", null, null, false));
        classified.put(4, model(4, SUPPORTING, DocumentType.INVOICE, null, null, null, false));

        PageClassificationResolver.Resolution resolution = PageClassificationResolver.resolve(4,
                Map.of(), classified);

        assertThat(resolution.pagesToExtract()).containsExactly(1, 2);
        assertThat(resolution.pages().get(1).documentRole()).isEqualTo(MAIN);
        assertThat(resolution.pages().get(1).documentType()).isEqualTo(DocumentType.OTHER);
        assertThat(resolution.pages().get(2).documentRole()).isEqualTo(OTHER);
        assertThat(resolution.warnings())
                .containsExactly("Page 2 classified SUPPORTING by the model but continues page 1: classified MAIN");
    }

    @Test
    void sameFormAsAHint_isPromotedToMain() {
        Map<Integer, PageClassification> hints = Map.of(1, hint(1, MAIN, "300.001"));
        Map<Integer, ClassifiedPage> classified = Map.of(
                2, model(2, SUPPORTING, DocumentType.FORM, "300.001", null, null, false));

        PageClassificationResolver.Resolution resolution = PageClassificationResolver.resolve(2, hints, classified);

        assertThat(resolution.pagesToExtract()).containsExactly(1, 2);
    }

    @Test
    void sameFormWhosePrintedNumberingStartsAgain_isAnotherDocument() {
        Map<Integer, ClassifiedPage> classified = new HashMap<>();
        classified.put(1, model(1, MAIN, DocumentType.FORM, "300.001", 1, 2, false));
        classified.put(2, model(2, MAIN, DocumentType.FORM, "300.001", 2, 2, true));
        classified.put(3, model(3, SUPPORTING, DocumentType.FORM, "300.001", 1, 2, false));
        classified.put(4, model(4, SUPPORTING, DocumentType.FORM, "300.001", 2, 2, true));

        PageClassificationResolver.Resolution resolution = PageClassificationResolver.resolve(4, Map.of(), classified);

        assertThat(resolution.pagesToExtract()).containsExactly(1, 2);
        assertThat(resolution.pages()).extracting(PageClassification::documentRole)
                .containsExactly(MAIN, MAIN, SUPPORTING, SUPPORTING);
        assertThat(resolution.warnings()).isEmpty();
    }

    @Test
    void hints_areKeptAsGiven_andUnclassifiedPagesAreExtracted() {
        Map<Integer, PageClassification> hints = Map.of(
                1, hint(1, MAIN, null),
                2, hint(2, SUPPORTING, null));
        Map<Integer, ClassifiedPage> classified = Map.of(
                4, model(4, OTHER, null, null, null, null, false));

        PageClassificationResolver.Resolution resolution = PageClassificationResolver.resolve(4, hints, classified);

        assertThat(resolution.pages()).extracting(PageClassification::page).containsExactly(1, 2, 4);
        assertThat(resolution.pages().get(1)).isSameAs(hints.get(2));
        assertThat(resolution.pagesToExtract()).as("page 3 could not be classified").containsExactly(1, 3);
    }

    @Test
    void noMainDocument_extractsAllPagesExceptOtherPages() {
        Map<Integer, ClassifiedPage> classified = Map.of(
                1, model(1, SUPPORTING, DocumentType.INVOICE, null, null, null, false),
                2, model(2, OTHER, null, null, null, null, false),
                3, model(3, SUPPORTING, DocumentType.INVOICE, null, null, null, false));

        PageClassificationResolver.Resolution resolution = PageClassificationResolver.resolve(3, Map.of(), classified);

        assertThat(resolution.pagesToExtract()).containsExactly(1, 3);
        assertThat(resolution.warnings())
                .containsExactly("No main document recognised: all the pages except blank pages are analysed");
    }

    @Test
    void onlyOtherPages_extractsAllPages() {
        PageClassificationResolver.Resolution resolution = PageClassificationResolver.resolve(2, Map.of(),
                Map.of(1, model(1, OTHER, null, null, null, null, false),
                        2, model(2, OTHER, null, null, null, null, false)));

        assertThat(resolution.pagesToExtract()).containsExactly(1, 2);
    }

    private static ClassifiedPage model(int page, DocumentRole role, DocumentType type, String formId,
                                        Integer printedPage, Integer printedCount, boolean continues) {
        return new ClassifiedPage(new PageClassification(page, role, type, formId, 0.8, null), printedPage,
                printedCount, continues);
    }

    private static PageClassification hint(int page, DocumentRole role, String formId) {
        return new PageClassification(page, role, null, formId, null, null);
    }
}
