package zas.admin.zia.translation.service.invoice;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Result of a successful extraction.
 *
 * @param documentType free classification of the document (informative)
 * @param warnings     messages written in the report of the result XML
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record ExtractionResponse(
        String documentType,
        Map<String, ExtractedField> fields,
        List<ExtractedService> services,
        List<String> warnings) {
}
