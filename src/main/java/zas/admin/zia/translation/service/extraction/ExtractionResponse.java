package zas.admin.zia.translation.service.extraction;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Result of a successful extraction.
 *
 * @param profile      id of the profile used, absent for an ad hoc request
 * @param documentType free classification of the document (informative)
 * @param fields       values found, by key; a key that was not found is absent (absent for a {@code perPage} profile)
 * @param collections  rows found for each requested collection (absent for a {@code perPage} profile)
 * @param warnings     problems met during the extraction
 * @param pages        classification of the pages of the file, absent when the pages are not classified
 * @param pageResults  values of each analysed page, in page order, present for a {@code perPage} profile only; a page
 *                     that could not be analysed is absent (with a warning)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExtractionResponse(
        String profile,
        String documentType,
        Map<String, ExtractedField> fields,
        Map<String, List<Map<String, ExtractedField>>> collections,
        List<String> warnings,
        List<PageClassification> pages,
        List<PageResult> pageResults) {

    public ExtractionResponse(String profile, String documentType, Map<String, ExtractedField> fields,
                              Map<String, List<Map<String, ExtractedField>>> collections, List<String> warnings,
                              List<PageClassification> pages) {
        this(profile, documentType, fields, collections, warnings, pages, null);
    }

    public ExtractionResponse(String profile, String documentType, Map<String, ExtractedField> fields,
                              Map<String, List<Map<String, ExtractedField>>> collections, List<String> warnings) {
        this(profile, documentType, fields, collections, warnings, null);
    }

    ExtractionResponse withPages(List<PageClassification> classifiedPages) {
        return new ExtractionResponse(profile, documentType, fields, collections, warnings,
                List.copyOf(classifiedPages), pageResults);
    }
}
