package zas.admin.zia.translation.service.extraction;

/**
 * Domain description used to classify the pages of a file, given by the profile. The generic rules (meaning of the
 * roles and of the answered keys) stay in the code.
 *
 * @param mainDocument short description of the main document (e.g. "the invoice addressed to the IV office"), used in
 *                     the prompts of the classification and of the extraction
 * @param instructions domain context to classify the pages (known forms, supporting documents...), may be empty
 */
public record PageClassificationSettings(String mainDocument, String instructions) {

    static final String DEFAULT_MAIN_DOCUMENT = "the main document of the file";

    /** Settings used when the client gives page hints without a profile that classifies its pages. */
    static final PageClassificationSettings DEFAULT = new PageClassificationSettings(null, null);

    public PageClassificationSettings {
        mainDocument = mainDocument == null || mainDocument.isBlank() ? DEFAULT_MAIN_DOCUMENT : mainDocument.strip();
        instructions = instructions == null ? "" : instructions.strip();
    }
}
