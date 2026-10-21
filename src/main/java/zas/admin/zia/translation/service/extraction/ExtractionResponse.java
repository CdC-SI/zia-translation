package zas.admin.zia.translation.service.extraction;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Result of a successful extraction.
 *
 * @param profile      id of the profile used, absent for an ad hoc request
 * @param documentType free classification of the document (informative)
 * @param fields       values found, by key; a key that was not found is absent
 * @param collections  rows found for each requested collection
 * @param warnings     problems met during the extraction
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExtractionResponse(
        String profile,
        String documentType,
        Map<String, ExtractedField> fields,
        Map<String, List<Map<String, ExtractedField>>> collections,
        List<String> warnings) {
}
