package zas.admin.zia.translation.service.invoice;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A value read in the document.
 *
 * @param confidence between 0 and 1
 * @param page       1-based page where the value was read
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record ExtractedField(String value, Double confidence, Integer page) {
}
