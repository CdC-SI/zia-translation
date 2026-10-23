package zas.admin.zia.translation.service.extraction;

import java.util.*;

/**
 * Merges the extractions of the pages of a document:
 * <ul>
 *     <li>a value found on several pages keeps the one with the highest confidence (the first page on a tie);</li>
 *     <li>the rows of a collection are concatenated in page order; a row identical to a row of another page (e.g. a
 *     repeated page) is dropped;</li>
 *     <li>the document type is the first one given.</li>
 * </ul>
 */
final class ExtractionResultMerger {

    private ExtractionResultMerger() {
    }

    /**
     * @param warnings warnings of the request and of the processing, put before the warnings of the pages
     */
    static ExtractionResponse merge(ExtractionRequest request, List<PageExtraction> pages, List<String> warnings) {
        Map<String, ExtractedField> fields = new LinkedHashMap<>();
        for (FieldDefinition field : request.fields()) {
            ExtractedField best = null;
            for (PageExtraction page : pages) {
                ExtractedField candidate = page.fields().get(field.key());
                if (candidate != null && (best == null || confidence(candidate) > confidence(best))) {
                    best = candidate;
                }
            }
            if (best != null) {
                fields.put(field.key(), best);
            }
        }

        Map<String, List<Map<String, ExtractedField>>> collections = new LinkedHashMap<>();
        for (CollectionDefinition collection : request.collections()) {
            collections.put(collection.key(), mergeRows(collection.key(), pages));
        }

        String documentType = pages.stream()
                .map(PageExtraction::documentType)
                .filter(type -> type != null && !type.isBlank())
                .findFirst()
                .orElse(null);

        Set<String> allWarnings = new LinkedHashSet<>(warnings);
        for (PageExtraction page : pages) {
            page.warnings().forEach(warning -> allWarnings.add("%s: %s".formatted(page.label(), warning)));
        }
        return new ExtractionResponse(request.profile(), documentType, fields,
                collections.isEmpty() ? null : collections, List.copyOf(allWarnings));
    }

    private static List<Map<String, ExtractedField>> mergeRows(String collection, List<PageExtraction> pages) {
        List<Map<String, ExtractedField>> rows = new ArrayList<>();
        Map<Map<String, String>, Integer> pageByRowValues = new HashMap<>();
        for (PageExtraction page : pages) {
            for (Map<String, ExtractedField> row : page.collections().getOrDefault(collection, List.of())) {
                if (row.isEmpty()) {
                    continue;
                }
                Map<String, String> values = new HashMap<>();
                row.forEach((name, field) -> values.put(name, field.value()));
                Integer firstPage = pageByRowValues.putIfAbsent(values, page.page());
                if (firstPage == null || firstPage == page.page()) {
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static double confidence(ExtractedField field) {
        return field.confidence() == null ? 0.0 : field.confidence();
    }
}
