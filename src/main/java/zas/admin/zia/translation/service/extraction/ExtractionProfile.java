package zas.admin.zia.translation.service.extraction;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-side description of what can be extracted from a kind of document.
 *
 * @param instructions       domain context added to the prompt of the model
 * @param pageClassification domain description to classify the pages of a file (main document, supporting
 *                           documents); {@code null} when the profile does not classify its pages
 * @param perPage            {@code true} when each page is extracted on its own and returned as is, without merging
 *                           the pages (see {@link ExtractionResponse#pageResults()})
 * @param simpleOutput       {@code true} to ask the model for the bare value of each field only (simple JSON
 *                           Schema, without evidence/confidence/page/documentType/warnings) and return only the
 *                           {@code value} of each field (structural warnings, e.g. a missing non-nullable value, are
 *                           still reported)
 */
public record ExtractionProfile(
        String id,
        String description,
        String instructions,
        Map<String, FieldDefinition> fields,
        Map<String, CollectionDefinition> collections,
        PageClassificationSettings pageClassification,
        boolean perPage,
        boolean simpleOutput) {

    public ExtractionProfile {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        collections = Collections.unmodifiableMap(new LinkedHashMap<>(collections));
        if (perPage && pageClassification != null) {
            throw new IllegalArgumentException("'perPage' and 'pageClassification' cannot be combined");
        }
    }

    /** Default profile: no page classification, not {@code perPage}, detailed (not simple) output. */
    public ExtractionProfile(String id, String description, String instructions, Map<String, FieldDefinition> fields,
                             Map<String, CollectionDefinition> collections) {
        this(id, description, instructions, fields, collections, null, false, false);
    }
}
