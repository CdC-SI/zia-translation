package zas.admin.zia.translation.service.extraction;

import java.util.List;
import java.util.Map;

/**
 * Values extracted from one page.
 *
 * @param collections rows of each requested collection, a row being the values of its fields
 */
record PageExtraction(
        int page,
        String documentType,
        Map<String, ExtractedField> fields,
        Map<String, List<Map<String, ExtractedField>>> collections,
        List<String> warnings) {
}
