package zas.admin.zia.translation.service.glossary;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * In-memory acronym glossary, loaded once from the taxonomy file.
 *
 * <p>Only concepts with at least one acronym in some language are kept, with all their entries: a language
 * without an acronym still needs its full name as a fallback target. On the bundled taxonomy_2026-10-01.json
 * this gives the same 1,664 concepts as the acronym-only glossary the evaluation gold sets are built on.
 */
@Component
@ConditionalOnProperty(name = "zia.translation.glossary.tool-enabled", havingValue = "true")
public class Glossary {

    private static final Logger log = LoggerFactory.getLogger(Glossary.class);

    /** Languages the taxonomy covers. */
    static final Set<String> LANGUAGES = Set.of("de", "fr", "it", "en");

    private static final Map<String, String> LANGUAGE_ALIASES = Map.ofEntries(
            Map.entry("german", "de"), Map.entry("deutsch", "de"), Map.entry("allemand", "de"), Map.entry("tedesco", "de"),
            Map.entry("french", "fr"), Map.entry("français", "fr"), Map.entry("francais", "fr"),
            Map.entry("französisch", "fr"), Map.entry("franzoesisch", "fr"), Map.entry("francese", "fr"),
            Map.entry("italian", "it"), Map.entry("italiano", "it"), Map.entry("italien", "it"), Map.entry("italienisch", "it"),
            Map.entry("english", "en"), Map.entry("anglais", "en"), Map.entry("englisch", "en"), Map.entry("inglese", "en"));

    private final List<GlossaryConcept> concepts;
    /** language → acronym → concepts having that acronym in that language. */
    private final Map<String, Map<String, List<GlossaryConcept>>> index;

    @Autowired
    public Glossary(ObjectMapper objectMapper,
                    @Value("${zia.translation.glossary.path}") Resource glossaryFile) {
        this(load(objectMapper, glossaryFile));
        log.info("Loaded acronym glossary from {}: {} concepts", glossaryFile.getDescription(), concepts.size());
    }

    Glossary(List<GlossaryConcept> allConcepts) {
        this.concepts = allConcepts.stream().filter(GlossaryConcept::hasAnyAcronym).toList();
        this.index = buildIndex(concepts);
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
        if (acronym == null || acronym.isBlank() || sourceLanguage == null || sourceLanguage.equals(targetLanguage)) {
            return List.of();
        }
        return index.getOrDefault(sourceLanguage, Map.of()).getOrDefault(acronym.strip(), List.of());
    }

    /**
     * The forms a translation should use for a concept in a language, the same rule as the evaluation gold:
     * the acronyms of the entries not flagged obsolete/rare/avoid; if there are none, their full names.
     */
    public static TargetForms targetForms(GlossaryConcept concept, String language) {
        List<GlossaryEntry> usable = concept.entries(language).stream()
                .filter(entry -> !entry.isFlagged())
                .toList();
        List<String> acronyms = usable.stream()
                .flatMap(entry -> entry.acronyms().stream())
                .collect(Collectors.toCollection(TreeSet::new))
                .stream().toList();
        if (!acronyms.isEmpty()) {
            return new TargetForms(acronyms, TargetFormKind.ACRONYM);
        }
        List<String> fullNames = fullNames(usable);
        if (!fullNames.isEmpty()) {
            return new TargetForms(fullNames, TargetFormKind.FULL_NAME);
        }
        return new TargetForms(List.of(), TargetFormKind.NONE);
    }

    /** Full names of the usable (not flagged) entries of a concept in a language, de-duplicated and sorted. */
    public static List<String> targetFullNames(GlossaryConcept concept, String language) {
        return fullNames(concept.entries(language).stream().filter(entry -> !entry.isFlagged()).toList());
    }

    /** The first non-blank definition of a concept in a language, preferring entries that are not flagged. */
    public static Optional<String> definition(GlossaryConcept concept, String language) {
        List<GlossaryEntry> entries = new ArrayList<>(concept.entries(language));
        entries.sort((a, b) -> Boolean.compare(a.isFlagged(), b.isFlagged()));
        return entries.stream()
                .map(GlossaryEntry::definition)
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(definition -> !definition.isEmpty())
                .findFirst();
    }

    private static List<String> fullNames(List<GlossaryEntry> entries) {
        return entries.stream()
                .map(GlossaryEntry::fullName)
                .filter(name -> name != null && !name.isBlank())
                .map(String::strip)
                .collect(Collectors.toCollection(TreeSet::new))
                .stream().toList();
    }

    private static Map<String, Map<String, List<GlossaryConcept>>> buildIndex(List<GlossaryConcept> concepts) {
        Map<String, Map<String, Set<GlossaryConcept>>> building = new HashMap<>();
        for (GlossaryConcept concept : concepts) {
            concept.languages().forEach((language, entries) -> {
                for (GlossaryEntry entry : entries) {
                    for (String acronym : entry.acronyms()) {
                        building.computeIfAbsent(language, l -> new HashMap<>())
                                .computeIfAbsent(acronym.strip(), a -> new LinkedHashSet<>())
                                .add(concept);
                    }
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
        try (InputStream in = glossaryFile.getInputStream()) {
            return objectMapper.readValue(in, new TypeReference<List<GlossaryConcept>>() { });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the acronym glossary " + glossaryFile.getDescription(), e);
        }
    }

    public record TargetForms(List<String> forms, TargetFormKind kind) { }

    public enum TargetFormKind {
        /** The concept has an official acronym in the target language. */
        ACRONYM,
        /** No acronym in the target language: write the full name. */
        FULL_NAME,
        /** No usable entry in the target language at all. */
        NONE
    }
}
