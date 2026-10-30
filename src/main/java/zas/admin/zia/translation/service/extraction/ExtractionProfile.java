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
 */
public record ExtractionProfile(
        String id,
        String description,
        String instructions,
        Map<String, FieldDefinition> fields,
        Map<String, CollectionDefinition> collections,
        PageClassificationSettings pageClassification) {

    public ExtractionProfile {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        collections = Collections.unmodifiableMap(new LinkedHashMap<>(collections));
    }

    public ExtractionProfile(String id, String description, String instructions, Map<String, FieldDefinition> fields,
                             Map<String, CollectionDefinition> collections) {
        this(id, description, instructions, fields, collections, null);
    }
}
