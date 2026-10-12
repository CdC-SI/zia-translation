package zas.admin.zia.translation.service.glossary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * The function the model can call during a translation. Spring AI turns the {@link Tool} method below into a
 * JSON Schema sent with every request, runs it when the model answers with a tool call, and sends the
 * returned list back to the model as JSON.
 */
@Component
@ConditionalOnProperty(name = "zia.translation.glossary.tool-enabled", havingValue = "true")
public class AcronymLookupTool {

    /** {@link ToolContext} key holding the glossary code of the target language ("fr", "de"…). */
    public static final String TARGET_LANGUAGE = "targetLanguage";

    static final int MAX_DEFINITION_LENGTH = 300;

    private static final Logger log = LoggerFactory.getLogger(AcronymLookupTool.class);

    private final Glossary glossary;

    public AcronymLookupTool(Glossary glossary) {
        this.glossary = glossary;
    }

    @Tool(name = "lookup_acronyms", description = """
            Looks up acronyms in the official glossary of Swiss social insurance terms (AHV/IV, EL, BVG…).
            For each acronym, returns the official forms to write in the target language (targetForms), \
            the official full names and a short definition. When an acronym has several candidates, \
            choose the one that fits the context. Call it once with every acronym of the source text, \
            and give the language of the source text. Only call it if the source text is in German, French, \
            Italian or English.""")
    public List<AcronymLookupResult> lookupAcronyms(
            @ToolParam(description = "Acronyms exactly as written in the source text, e.g. [\"AHV\", \"IV\", \"EL\"]")
            List<String> acronyms,
            @ToolParam(description = "Language of the source text: DE (German), FR (French), IT (Italian), EN (English)")
            SourceLanguage sourceLanguage,
            ToolContext toolContext) {

        String targetLanguage = (String) toolContext.getContext().get(TARGET_LANGUAGE);
        // The schema marks sourceLanguage as required, but nothing enforces it: without it, nothing is looked up.
        String source = sourceLanguage == null ? null : sourceLanguage.code();
        if (source == null) {
            log.warn("lookup_acronyms called without a source language: every acronym is reported not found");
        }
        List<AcronymLookupResult> results = (acronyms == null ? List.<String>of() : acronyms).stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(acronym -> !acronym.isEmpty())
                .distinct()
                .map(acronym -> lookup(acronym, source, targetLanguage))
                .toList();

        log.info("lookup_acronyms (source '{}', target '{}'): found {}, not found {}", source, targetLanguage,
                results.stream().filter(AcronymLookupResult::found).map(AcronymLookupResult::acronym).toList(),
                results.stream().filter(r -> !r.found()).map(AcronymLookupResult::acronym).toList());
        return results;
    }

    private AcronymLookupResult lookup(String acronym, String sourceLanguage, String targetLanguage) {
        List<Candidate> candidates = glossary.lookup(acronym, sourceLanguage, targetLanguage).stream()
                .map(concept -> toCandidate(concept, targetLanguage))
                .toList();
        return new AcronymLookupResult(acronym, !candidates.isEmpty(), candidates);
    }

    private static Candidate toCandidate(GlossaryConcept concept, String targetLanguage) {
        Glossary.TargetForms targetForms = Glossary.targetForms(concept, targetLanguage);
        return new Candidate(
                concept.conceptId(),
                targetForms.forms(),
                targetForms.kind(),
                Glossary.targetFullNames(concept, targetLanguage),
                Glossary.definition(concept, targetLanguage).map(AcronymLookupTool::truncate).orElse(null));
    }

    static String truncate(String text) {
        if (text.length() <= MAX_DEFINITION_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_DEFINITION_LENGTH - 1).stripTrailing() + "…";
    }

    /** What the model receives for one acronym. {@code found} is false when the glossary doesn't know it. */
    public record AcronymLookupResult(String acronym, boolean found, List<Candidate> candidates) { }

    /**
     * One concept the acronym can stand for. {@code targetForms} are the forms to write in the translation
     * ({@code targetFormKind} says whether they are acronyms or, when the target language has none, full names).
     */
    public record Candidate(
            String conceptId,
            List<String> targetForms,
            Glossary.TargetFormKind targetFormKind,
            List<String> targetFullNames,
            String definition) { }
}
