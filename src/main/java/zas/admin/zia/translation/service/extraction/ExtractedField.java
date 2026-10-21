package zas.admin.zia.translation.service.extraction;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A value read in the document.
 *
 * @param value      normalized value
 * @param confidence between 0 and 1, computed by the service
 * @param page       1-based page where the value was read
 * @param evidence   text read in the document by the model
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExtractedField(String value, Double confidence, Integer page, String evidence) {
}
