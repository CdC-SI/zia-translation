package zas.admin.zia.translation.service.extraction;

import java.util.*;

/**
 * {@code context} part of a submission: what the client already knows and what must be extracted.
 *
 * @param profile       optional id of a server-side {@link ExtractionProfile}
 * @param knownFields   values already known by the client, given to the model as hints
 * @param missingFields keys of the values (and collections) to extract
 * @param instructions  optional additional instructions for the model
 * @param pageHints     classification of pages already known by the client, to be confirmed or completed
 */
public record ExtractionContext(
        String profile,
        String documentId,
        String language,
        Map<String, String> knownFields,
        List<String> missingFields,
        String instructions,
        List<PageClassification> pageHints) {

    public ExtractionContext {
        profile = profile == null || profile.isBlank() ? null : profile.strip();
        knownFields = knownFields == null ? Map.of() : withoutNulls(knownFields);
        missingFields = missingFields == null
                ? List.of()
                : missingFields.stream().filter(Objects::nonNull).map(String::strip).distinct().toList();
        pageHints = pageHints == null ? List.of() : pageHints.stream().filter(Objects::nonNull).toList();
    }

    public ExtractionContext(String profile, String documentId, String language, Map<String, String> knownFields,
                             List<String> missingFields, String instructions) {
        this(profile, documentId, language, knownFields, missingFields, instructions, null);
    }

    private static Map<String, String> withoutNulls(Map<String, String> map) {
        Map<String, String> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(key, value);
            }
        });
        return Collections.unmodifiableMap(copy);
    }
}
