package zas.admin.zia.translation.service.glossary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A concept in one language, as decided by the glossary export ({@code export_glossary.py} in
 * glossary_scraping): one form to write, the best definition, and every form to recognise.
 *
 * @param acronym         the one acronym to write in a translation, or null when the language has none
 * @param fullName        the one full name; the target when {@code acronym} is null
 * @param definition      the best definition (ahv-iv.ch, else TERMDAT without its coded notes), or null
 * @param detect          every acronym of the concept in this language, old or discouraged ones included:
 *                        recognised in source text, never written
 * @param detectFullNames every full name, same purpose (for the coming lookup by full name)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GlossaryLanguage(
        @JsonProperty("acronym") String acronym,
        @JsonProperty("full_name") String fullName,
        @JsonProperty("definition") String definition,
        @JsonProperty("detect") List<String> detect,
        @JsonProperty("detect_full_names") List<String> detectFullNames) {

    public GlossaryLanguage {
        detect = detect == null ? List.of() : List.copyOf(detect);
        detectFullNames = detectFullNames == null ? List.of() : List.copyOf(detectFullNames);
    }
}
