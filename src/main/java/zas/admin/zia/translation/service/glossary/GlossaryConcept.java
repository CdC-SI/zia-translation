package zas.admin.zia.translation.service.glossary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * One concept of the taxonomy file (e.g. "old-age and survivors' insurance"), with its entries per language
 * ("de", "fr", "it", "en"). Fields of the file not needed here are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GlossaryConcept(
        @JsonProperty("concept_id") String conceptId,
        @JsonProperty("languages") Map<String, List<GlossaryEntry>> languages) {

    public GlossaryConcept {
        languages = languages == null ? Map.of() : languages;
    }

    public List<GlossaryEntry> entries(String language) {
        return languages.getOrDefault(language, List.of());
    }

    boolean hasAnyAcronym() {
        return languages.values().stream()
                .flatMap(List::stream)
                .anyMatch(entry -> !entry.acronyms().isEmpty());
    }
}
