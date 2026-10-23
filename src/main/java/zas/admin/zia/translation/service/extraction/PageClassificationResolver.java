package zas.admin.zia.translation.service.extraction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Consolidates the classification of the pages of a file and selects the pages to extract.
 * <ul>
 *     <li>the hints of the client are kept as given;</li>
 *     <li>a page classified by the model that continues a page of the main document (same form, printed numbering
 *     that follows, or continuation according to the model) is a page of the main document;</li>
 *     <li>the pages to extract are the pages of the main document and the unclassified pages (treated like pages of
 *     the main document); without main document, all the pages except the {@link DocumentRole#OTHER} ones.</li>
 * </ul>
 */
final class PageClassificationResolver {

    private PageClassificationResolver() {
    }

    /**
     * @param pages         classification of the pages, sorted, unclassified pages absent
     * @param pagesToExtract pages to send to the extraction, sorted
     * @param warnings      warnings of the consolidation
     */
    record Resolution(List<PageClassification> pages, List<Integer> pagesToExtract, List<String> warnings) {
    }

    /**
     * @param pageCount  number of analysed pages
     * @param hints      classification given by the client, by page
     * @param classified classification by the model, by page
     */
    static Resolution resolve(int pageCount, Map<Integer, PageClassification> hints,
                              Map<Integer, ClassifiedPage> classified) {
        List<String> warnings = new ArrayList<>();
        PageClassification[] pages = new PageClassification[pageCount + 1];
        for (int page = 1; page <= pageCount; page++) {
            PageClassification hint = hints.get(page);
            ClassifiedPage model = classified.get(page);
            if (hint != null) {
                pages[page] = hint;
            } else if (model != null) {
                pages[page] = continuesMainDocument(pages[page - 1], classified.get(page - 1), model)
                        ? promoteToMain(model.classification(), page - 1, warnings)
                        : model.classification();
            }
        }

        List<PageClassification> result = new ArrayList<>();
        List<Integer> main = new ArrayList<>();
        List<Integer> unknown = new ArrayList<>();
        List<Integer> notOther = new ArrayList<>();
        for (int page = 1; page <= pageCount; page++) {
            PageClassification classification = pages[page];
            if (classification == null) {
                unknown.add(page);
                notOther.add(page);
                continue;
            }
            result.add(classification);
            if (classification.documentRole() == DocumentRole.MAIN) {
                main.add(page);
            }
            if (classification.documentRole() != DocumentRole.OTHER) {
                notOther.add(page);
            }
        }

        List<Integer> toExtract;
        if (!main.isEmpty()) {
            toExtract = new ArrayList<>(main);
            toExtract.addAll(unknown);
            toExtract.sort(null);
        } else {
            warnings.add("No main document recognised: all the pages except blank pages are analysed");
            toExtract = notOther.isEmpty() ? allPages(pageCount) : notOther;
        }
        return new Resolution(List.copyOf(result), List.copyOf(toExtract), List.copyOf(warnings));
    }

    private static boolean continuesMainDocument(PageClassification previous, ClassifiedPage previousModel,
                                                 ClassifiedPage current) {
        if (previous == null || previous.documentRole() != DocumentRole.MAIN
                || current.classification().documentRole() == DocumentRole.MAIN) {
            return false;
        }
        String formId = current.classification().formId();
        boolean sameForm = formId != null && formId.equals(previous.formId());
        boolean numberingContinues = previousModel != null
                && previousModel.printedPageNumber() != null && current.printedPageNumber() != null
                && current.printedPageNumber() == previousModel.printedPageNumber() + 1
                && Objects.equals(current.printedPageCount(), previousModel.printedPageCount());
        return sameForm || numberingContinues || current.continuesPreviousPage();
    }

    private static PageClassification promoteToMain(PageClassification classification, int previousPage,
                                                    List<String> warnings) {
        warnings.add("Page %d classified %s by the model but continues page %d: classified MAIN"
                .formatted(classification.page(), classification.documentRole(), previousPage));
        return new PageClassification(classification.page(), DocumentRole.MAIN, classification.documentType(),
                classification.formId(), classification.confidence(), classification.evidence());
    }

    private static List<Integer> allPages(int pageCount) {
        List<Integer> pages = new ArrayList<>(pageCount);
        for (int page = 1; page <= pageCount; page++) {
            pages.add(page);
        }
        return pages;
    }
}
