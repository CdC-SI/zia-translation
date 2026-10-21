package zas.admin.zia.translation.service.glossary;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The function the model can call during a translation. Spring AI turns the {@link Tool} method below into a
 * JSON Schema sent with every request, runs it when the model answers with a tool call, and sends the
 * returned list back to the model as JSON.
 */
@Component
@ConditionalOnProperty(name = "zia.translation.glossary.tool-enabled", havingValue = "true")
public class AcronymLookupTool {

    /** The tool's name, as the model sees it (also used to cap its calls, see AiClientConfiguration). */
    public static final String TOOL_NAME = "lookup_acronyms";

    /** {@link ToolContext} key holding the glossary code of the target language ("fr", "de"…). */
    public static final String TARGET_LANGUAGE = "targetLanguage";

    static final int MAX_DEFINITION_LENGTH = 300;

    /** Notes for a lookup whose source language is the target language (the model checking its own forms). */
    static final String SAME_LANGUAGE_KNOWN = "Already in the target language: write the targetForm of the candidate "
            + "that fits (usually this acronym itself). Do not look it up again.";
    static final String SAME_LANGUAGE_UNKNOWN = "The text is already in the target language: there is nothing to "
            + "translate. Keep this acronym as written and do not look it up again.";

    private static final Logger log = LoggerFactory.getLogger(AcronymLookupTool.class);

    private final Glossary glossary;

    public AcronymLookupTool(Glossary glossary) {
        this.glossary = glossary;
    }

    @Tool(name = TOOL_NAME, description = """
        Looks up acronyms in the official glossary of Swiss social insurance terms.
        For each acronym, returns the official forms to write in the target language (targetForms), \
        the official full names and a short definition. When an acronym has several candidates, \
        choose the one that fits the context; if none fits, the acronym has another meaning in this text. \
        Call it once with every acronym of the source text, and give the language of the source text. \
        Only call it if the source text is in German, French, Italian or English.""")
    public List<AcronymLookupResult> lookupAcronyms(
            @ToolParam(description = "One item per acronym, copied letter by letter as written in the source text. "
                + "Split combined forms such as A/B into separate items.")
            List<String> acronyms,
            @ToolParam(description = "Language of the sentences around the acronyms (not of the acronyms themselves): "
                + "DE (German), FR (French), IT (Italian), EN (English)")
            SourceLanguage sourceLanguage,
            ToolContext toolContext) {

        String targetLanguage = (String) toolContext.getContext().get(TARGET_LANGUAGE);
        if (sourceLanguage == null) {
            // Spring AI returns this message to the model as the tool result, so the model can call again.
            log.warn("lookup_acronyms called without a source language: asking the model to call again");
            throw new IllegalArgumentException("sourceLanguage is missing. Call lookup_acronyms again with the same "
                    + "acronyms and sourceLanguage DE, FR, IT or EN.");
        }
        String source = sourceLanguage.code();
        List<AcronymLookupResult> results = splitAcronyms(acronyms).stream()
                .map(acronym -> lookup(acronym, source, targetLanguage))
                .toList();

        log.info("lookup_acronyms (source '{}', target '{}'{}): found {}, not found {}", source, targetLanguage,
                source.equals(targetLanguage) ? ", same language" : "",
                results.stream().filter(AcronymLookupResult::found).map(AcronymLookupResult::acronym).toList(),
                results.stream().filter(r -> !r.found()).map(AcronymLookupResult::acronym).toList());
        return results;
    }

    /** The items to look up: "AHV/IV" becomes "AHV" and "IV"; blank and repeated items are dropped. */
    static List<String> splitAcronyms(List<String> acronyms) {
        return (acronyms == null ? List.<String>of() : acronyms).stream()
                .filter(Objects::nonNull)
                .flatMap(acronym -> Arrays.stream(acronym.split("/")))
                .map(String::strip)
                .filter(acronym -> !acronym.isEmpty())
                .distinct()
                .toList();
    }

    private AcronymLookupResult lookup(String acronym, String sourceLanguage, String targetLanguage) {
        if (sourceLanguage.equals(targetLanguage)) {
            // The model is checking a form of the target language: say so, instead of a bare "not found"
            // that makes it try again with other source languages.
            List<Candidate> candidates = glossary.conceptsWithAcronym(acronym, targetLanguage).stream()
                    .map(concept -> toCandidate(concept, targetLanguage))
                    .toList();
            return new AcronymLookupResult(acronym, !candidates.isEmpty(), candidates,
                    candidates.isEmpty() ? SAME_LANGUAGE_UNKNOWN : SAME_LANGUAGE_KNOWN);
        }
        List<Candidate> candidates = glossary.lookup(acronym, sourceLanguage, targetLanguage).stream()
                .map(concept -> toCandidate(concept, targetLanguage))
                .toList();
        return new AcronymLookupResult(acronym, !candidates.isEmpty(), candidates, null);
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

    /**
     * What the model receives for one acronym. {@code found} is false when the glossary doesn't know it;
     * {@code note} is an instruction for special cases (source language = target language), absent otherwise.
     */
    public record AcronymLookupResult(
            String acronym,
            boolean found,
            List<Candidate> candidates,
            @JsonInclude(JsonInclude.Include.NON_NULL) String note) { }

    /**
     * One concept the acronym can stand for. {@code targetForms} holds the one form to write in the translation
     * ({@code targetFormKind} says whether it is an acronym or, when the target language has none, a full name).
     */
    public record Candidate(
            String conceptId,
            List<String> targetForms,
            Glossary.TargetFormKind targetFormKind,
            List<String> targetFullNames,
            String definition) { }
}
