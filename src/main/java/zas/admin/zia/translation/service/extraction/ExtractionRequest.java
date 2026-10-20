package zas.admin.zia.translation.service.extraction;

import java.util.List;
import java.util.Map;

/**
 * Validated extraction request: the definitions of the requested values, resolved from the profile or ad hoc.
 *
 * @param profile             id of the profile, {@code null} for an ad hoc request
 * @param profileInstructions domain instructions of the profile
 * @param warnings            warnings of the resolution (e.g. ignored keys), returned in the response
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
        List<String> warnings) {

    ExtractionRequest {
        knownFields = knownFields == null ? Map.of() : knownFields;
        fields = List.copyOf(fields);
        collections = List.copyOf(collections);
        warnings = List.copyOf(warnings);
    }
}
