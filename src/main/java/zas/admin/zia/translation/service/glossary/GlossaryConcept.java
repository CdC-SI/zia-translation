package zas.admin.zia.translation.service.glossary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.Optional;

/**
 * One concept of the glossary file (e.g. "old-age and survivors' insurance"), with its decided forms per
 * language ("de", "fr", "it", "en"). Fields of the file not needed here are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GlossaryConcept(
        @JsonProperty("concept_id") String conceptId,
        @JsonProperty("languages") Map<String, GlossaryLanguage> languages) {

    public GlossaryConcept {
        languages = languages == null ? Map.of() : Map.copyOf(languages);
    }

    public Optional<GlossaryLanguage> language(String language) {
        return Optional.ofNullable(languages.get(language));
    }
}
