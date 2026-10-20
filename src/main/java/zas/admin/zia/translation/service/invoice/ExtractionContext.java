package zas.admin.zia.translation.service.invoice;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code context} part of a submission: what the client already knows and what is still missing.
 */
record ExtractionContext(
        String documentId,
        String language,
        String officeAI,
        Map<String, String> knownFields,
        List<String> missingFields) {

    ExtractionContext {
        knownFields = knownFields == null ? Map.of() : withoutNulls(knownFields);
        missingFields = missingFields == null
                ? List.of()
                : missingFields.stream().filter(Objects::nonNull).map(String::strip).toList();
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