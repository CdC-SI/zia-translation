package zas.admin.zia.translation.service.extraction;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Classification of one page of the file: given by the client ({@code pageHints}, without confidence nor evidence)
 * or returned by the service ({@code pages}).
 *
 * @param page       1-based page of the file
 * @param formId     identifier printed on a form (e.g. {@code 300.001}), when the page belongs to a form
 * @param confidence between 0 and 1, absent for a hint of the client
 * @param evidence   text that justifies the classification (title, recipient...)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PageClassification(
        int page,
        DocumentRole documentRole,
        DocumentType documentType,
        String formId,
        Double confidence,
        String evidence) {

    static final int MAX_FORM_ID_LENGTH = 35;

    public PageClassification {
        formId = formId == null || formId.isBlank() ? null : formId.strip();
        evidence = evidence == null || evidence.isBlank() ? null : evidence.strip();
    }
}
