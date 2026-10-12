package zas.admin.zia.translation.service.glossary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One name of a concept in one language, from one source (the ahv-iv.ch glossary or TERMDAT).
 * {@code obsolete}, {@code rare} and {@code avoid} mark forms that must not be used as a translation target.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GlossaryEntry(
        @JsonProperty("origin") String origin,
        @JsonProperty("full_name") String fullName,
        @JsonProperty("acronyms") List<String> acronyms,
        @JsonProperty("definition") String definition,
        @JsonProperty("obsolete") boolean obsolete,
        @JsonProperty("rare") boolean rare,
        @JsonProperty("avoid") boolean avoid) {

    public GlossaryEntry {
        acronyms = acronyms == null ? List.of() : List.copyOf(acronyms);
    }

    boolean isFlagged() {
        return obsolete || rare || avoid;
    }
}
