package zas.admin.zia.translation.service.glossary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory acronym glossary, loaded once from the glossary file ({@code glossary_<date>.json}, written by
 * {@code export_glossary.py} in glossary_scraping).
 *
 * <p>Every choice is made by the export: one acronym and one full name per concept and language, the best
 * definition, and the forms to recognise ({@code detect}, old or discouraged forms included). This class only
 * indexes and reads them. The file holds only concepts with at least one acronym.
 */
@Component
@ConditionalOnProperty(name = "zia.translation.glossary.tool-enabled", havingValue = "true")
public class Glossary {

    /** The only file format this class reads (the {@code schema} field of the file). */
    static final String SCHEMA = "translation-glossary-v1";

    private static final Logger log = LoggerFactory.getLogger(Glossary.class);

    /** Languages the glossary covers. */
    static final Set<String> LANGUAGES = Set.of("de", "fr", "it", "en");

    private static final Map<String, String> LANGUAGE_ALIASES = Map.ofEntries(
            Map.entry("german", "de"), Map.entry("deutsch", "de"), Map.entry("allemand", "de"), Map.entry("tedesco", "de"),
            Map.entry("french", "fr"), Map.entry("français", "fr"), Map.entry("francais", "fr"),
            Map.entry("französisch", "fr"), Map.entry("franzoesisch", "fr"), Map.entry("francese", "fr"),
            Map.entry("italian", "it"), Map.entry("italiano", "it"), Map.entry("italien", "it"), Map.entry("italienisch", "it"),
            Map.entry("english", "en"), Map.entry("anglais", "en"), Map.entry("englisch", "en"), Map.entry("inglese", "en"));

    private final List<GlossaryConcept> concepts;
    /** language → acronym (any form in {@code detect}) → concepts having that acronym in that language. */
    private final Map<String, Map<String, List<GlossaryConcept>>> index;

    @Autowired
    public Glossary(ObjectMapper objectMapper,
                    @Value("${zia.translation.glossary.path}") Resource glossaryFile) {
        this(load(objectMapper, glossaryFile));
        log.info("Loaded acronym glossary from {}: {} concepts", glossaryFile.getDescription(), concepts.size());
    }

    Glossary(List<GlossaryConcept> concepts) {
        this.concepts = List.copyOf(concepts);
        this.index = buildIndex(this.concepts);
    }

    public int size() {
        return concepts.size();
    }

    /**
     * Maps a free-text target language ("fr", "fr-CH", "French", "français"…) to a glossary language code.
     */
    public static Optional<String> languageCode(String targetLanguage) {
        if (targetLanguage == null) {
            return Optional.empty();
        }
        String normalized = targetLanguage.strip().toLowerCase(Locale.ROOT);
        String primary = normalized.split("[-_]", 2)[0];
        if (LANGUAGES.contains(primary)) {
            return Optional.of(primary);
        }
        return Optional.ofNullable(LANGUAGE_ALIASES.get(normalized));
    }

    /**
     * Finds the concepts an acronym can stand for in the source language only. Several concepts mean the
     * acronym is ambiguous in that language. Empty when there is nothing to translate: blank acronym, no
     * source language, or a source language equal to the target.
     */
    public List<GlossaryConcept> lookup(String acronym, String sourceLanguage, String targetLanguage) {
        if (sourceLanguage == null || sourceLanguage.equals(targetLanguage)) {
            return List.of();
        }
        return conceptsWithAcronym(acronym, sourceLanguage);
    }

    /** The concepts having this acronym (any recognised form) in a language, whatever the target. */
    public List<GlossaryConcept> conceptsWithAcronym(String acronym, String language) {
        if (acronym == null || acronym.isBlank() || language == null) {
            return List.of();
        }
        return index.getOrDefault(language, Map.of()).getOrDefault(acronym.strip(), List.of());
    }

    /**
     * The form a translation should use for a concept in a language: its acronym; if the language has none,
     * its full name; if it has neither, nothing.
     */
    public static TargetForms targetForms(GlossaryConcept concept, String language) {
        Optional<GlossaryLanguage> forms = concept.language(language);
        Optional<String> acronym = forms.map(GlossaryLanguage::acronym).filter(Glossary::isPresent);
        if (acronym.isPresent()) {
            return new TargetForms(List.of(acronym.get().strip()), TargetFormKind.ACRONYM);
        }
        Optional<String> fullName = forms.map(GlossaryLanguage::fullName).filter(Glossary::isPresent);
        return fullName.map(name -> new TargetForms(List.of(name.strip()), TargetFormKind.FULL_NAME))
                .orElseGet(() -> new TargetForms(List.of(), TargetFormKind.NONE));
    }

    /** The concept's full name in a language (one at most). */
    public static List<String> targetFullNames(GlossaryConcept concept, String language) {
        return concept.language(language).map(GlossaryLanguage::fullName).filter(Glossary::isPresent)
                .map(name -> List.of(name.strip())).orElse(List.of());
    }

    /** The concept's definition in a language, if it has one. */
    public static Optional<String> definition(GlossaryConcept concept, String language) {
        return concept.language(language).map(GlossaryLanguage::definition).filter(Glossary::isPresent)
                .map(String::strip);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static Map<String, Map<String, List<GlossaryConcept>>> buildIndex(List<GlossaryConcept> concepts) {
        Map<String, Map<String, Set<GlossaryConcept>>> building = new HashMap<>();
        for (GlossaryConcept concept : concepts) {
            concept.languages().forEach((language, forms) -> {
                for (String acronym : forms.detect()) {
                    building.computeIfAbsent(language, l -> new HashMap<>())
                            .computeIfAbsent(acronym.strip(), a -> new LinkedHashSet<>())
                            .add(concept);
                }
            });
        }
        Map<String, Map<String, List<GlossaryConcept>>> index = new HashMap<>();
        building.forEach((language, byAcronym) -> {
            Map<String, List<GlossaryConcept>> frozen = new HashMap<>();
            byAcronym.forEach((acronym, set) -> frozen.put(acronym, List.copyOf(set)));
            index.put(language, Map.copyOf(frozen));
        });
        return Map.copyOf(index);
    }

    private static List<GlossaryConcept> load(ObjectMapper objectMapper, Resource glossaryFile) {
        GlossaryFile file;
        try (InputStream in = glossaryFile.getInputStream()) {
            file = objectMapper.readValue(in, GlossaryFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the acronym glossary " + glossaryFile.getDescription(), e);
        }
        if (!SCHEMA.equals(file.schema())) {
            throw new IllegalStateException("The acronym glossary " + glossaryFile.getDescription() + " has schema '"
                    + file.schema() + "', expected '" + SCHEMA + "' (generate it with export_glossary.py)");
        }
        return file.concepts() == null ? List.of() : file.concepts();
    }

    /** The glossary file: {@code {"schema": …, "source": …, "concepts": [...]}}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record GlossaryFile(
            @JsonProperty("schema") String schema,
            @JsonProperty("source") String source,
            @JsonProperty("concepts") List<GlossaryConcept> concepts) { }

    public record TargetForms(List<String> forms, TargetFormKind kind) { }

    public enum TargetFormKind {
        /** The concept has an official acronym in the target language. */
        ACRONYM,
        /** No acronym in the target language: write the full name. */
        FULL_NAME,
        /** No form in the target language at all. */
        NONE
    }
}
