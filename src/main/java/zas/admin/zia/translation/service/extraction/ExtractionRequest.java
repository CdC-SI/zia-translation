package zas.admin.zia.translation.service.extraction;

import java.util.List;
import java.util.Map;

/**
 * Validated extraction request: the definitions of the requested values, resolved from the profile or ad hoc.
 *
 * @param profile             id of the profile, {@code null} for an ad hoc request
 * @param profileInstructions domain instructions of the profile
 * @param warnings            warnings of the resolution (e.g. ignored keys), returned in the response
 * @param pageHints           classification of pages given by the client
 * @param pageClassification  domain description of the profile to classify the pages, {@code null} when the
 *                            profile does not classify its pages
 * @param perPage             each page is extracted on its own and returned without merging
 * @param simpleOutput        the model is asked for the bare value of each field only (simple schema) and each
 *                            field of the response only carries its {@code value}
 */
record ExtractionRequest(
        String profile,
        String profileInstructions,
        String documentId,
        String language,
        Map<String, String> knownFields,
        String instructions,
        List<FieldDefinition> fields,
        List<CollectionDefinition> collections,
        List<String> warnings,
        List<PageClassification> pageHints,
        PageClassificationSettings pageClassification,
        boolean perPage,
        boolean simpleOutput) {

    ExtractionRequest {
        knownFields = knownFields == null ? Map.of() : knownFields;
        fields = List.copyOf(fields);
        collections = List.copyOf(collections);
        warnings = List.copyOf(warnings);
        pageHints = pageHints == null ? List.of() : List.copyOf(pageHints);
    }

    ExtractionRequest(String profile, String profileInstructions, String documentId, String language,
                      Map<String, String> knownFields, String instructions, List<FieldDefinition> fields,
                      List<CollectionDefinition> collections, List<String> warnings,
                      List<PageClassification> pageHints, PageClassificationSettings pageClassification) {
        this(profile, profileInstructions, documentId, language, knownFields, instructions, fields, collections,
                warnings, pageHints, pageClassification, false, false);
    }

    ExtractionRequest(String profile, String profileInstructions, String documentId, String language,
                      Map<String, String> knownFields, String instructions, List<FieldDefinition> fields,
                      List<CollectionDefinition> collections, List<String> warnings) {
        this(profile, profileInstructions, documentId, language, knownFields, instructions, fields, collections,
                warnings, null, null);
    }

    /**
     * The pages are classified when the profile classifies its pages or when the client gives page hints, never for
     * a {@code perPage} profile.
     */
    boolean classifiesPages() {
        return !perPage && (pageClassification != null || !pageHints.isEmpty());
    }

    /** Domain description of the pages, generic when the profile does not describe them. */
    PageClassificationSettings pageClassificationSettings() {
        return pageClassification == null ? PageClassificationSettings.DEFAULT : pageClassification;
    }
}
