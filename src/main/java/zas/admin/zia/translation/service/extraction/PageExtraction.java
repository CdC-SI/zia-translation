package zas.admin.zia.translation.service.extraction;

import java.util.List;
import java.util.Map;

/**
 * Values extracted from one call of the vision model: one page, or several pages of the main document.
 *
 * @param page        first page sent
 * @param pages       pages sent, in order
 * @param collections rows of each requested collection, a row being the values of its fields
 */
record PageExtraction(
        int page,
        List<Integer> pages,
        String documentType,
        Map<String, ExtractedField> fields,
        Map<String, List<Map<String, ExtractedField>>> collections,
        List<String> warnings) {

    PageExtraction {
        pages = pages == null || pages.isEmpty() ? List.of(page) : List.copyOf(pages);
    }

    PageExtraction(int page, String documentType, Map<String, ExtractedField> fields,
                   Map<String, List<Map<String, ExtractedField>>> collections, List<String> warnings) {
        this(page, List.of(page), documentType, fields, collections, warnings);
    }

    /** Prefix of the warnings of this extraction, e.g. {@code Page 2} or {@code Pages 1, 2}. */
    String label() {
        if (pages.size() == 1) {
            return "Page " + pages.getFirst();
        }
        return "Pages " + String.join(", ", pages.stream().map(String::valueOf).toList());
    }
}
