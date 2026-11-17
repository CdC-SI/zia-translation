package zas.admin.zia.translation.service.extraction;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Values extracted from one page of the file, returned without merging with the other pages ({@code perPage}
 * profiles).
 *
 * @param page        1-based page of the file
 * @param fields      values found on the page, by key; a key that was not found is absent
 * @param collections rows found on the page for each requested collection
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PageResult(
        int page,
        String documentType,
        Map<String, ExtractedField> fields,
        Map<String, List<Map<String, ExtractedField>>> collections) {
}
