package zas.admin.zia.translation.service.glossary;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AcronymLookupToolTest {

    private static AcronymLookupTool tool;

    @BeforeAll
    static void setUp() {
        tool = new AcronymLookupTool(new Glossary(JsonMapper.builder().build(), new ClassPathResource("glossary/glossary-fixture.json")));
    }

    @Test
    void lookupAcronyms_usesTheTargetLanguageFromTheToolContext() {
        List<AcronymLookupTool.AcronymLookupResult> toFrench = tool.lookupAcronyms(List.of("AHV"), SourceLanguage.DE, context("fr"));
        List<AcronymLookupTool.AcronymLookupResult> toGerman = tool.lookupAcronyms(List.of("AVS"), SourceLanguage.FR, context("de"));

        assertThat(toFrench.getFirst().candidates().getFirst().targetForms()).containsExactly("AVS");
        assertThat(toGerman.getFirst().candidates().getFirst().targetForms()).containsExactly("AHV");
    }

    @Test
    void lookupAcronyms_searchesOnlyTheSourceLanguage() {
        AcronymLookupTool.AcronymLookupResult fromGerman = tool.lookupAcronyms(List.of("EPA"), SourceLanguage.DE, context("fr")).getFirst();
        AcronymLookupTool.AcronymLookupResult fromItalian = tool.lookupAcronyms(List.of("EPA"), SourceLanguage.IT, context("fr")).getFirst();

        assertThat(fromGerman.candidates()).extracting(AcronymLookupTool.Candidate::conceptId).containsExactly("test:epa-de");
        assertThat(fromItalian.found()).isFalse();
    }

    @Test
    void lookupAcronyms_returnsOneResultPerDistinctAcronym() {
        List<AcronymLookupTool.AcronymLookupResult> results =
                tool.lookupAcronyms(List.of("AHV", " AHV ", "XYZ", "", "AK"), SourceLanguage.DE, context("fr"));

        assertThat(results).extracting(AcronymLookupTool.AcronymLookupResult::acronym).containsExactly("AHV", "XYZ", "AK");
        assertThat(results).extracting(AcronymLookupTool.AcronymLookupResult::found).containsExactly(true, false, true);
        assertThat(results.get(2).candidates()).hasSize(2);
    }

    @Test
    void lookupAcronyms_withoutSourceLanguage_asksTheModelToCallAgain() {
        assertThatThrownBy(() -> tool.lookupAcronyms(List.of("AHV"), null, context("fr")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceLanguage");
    }

    @Test
    void lookupAcronyms_splitsSlashPairs() {
        assertThat(tool.lookupAcronyms(List.of("AHV/IV", "AHV"), SourceLanguage.DE, context("fr")))
                .extracting(AcronymLookupTool.AcronymLookupResult::acronym)
                .containsExactly("AHV", "IV");
    }


    @Test
    void lookupAcronyms_nullList_returnsEmpty() {
        assertThat(tool.lookupAcronyms(null, SourceLanguage.DE, context("fr"))).isEmpty();
    }

    @Test
    void lookupAcronyms_sourceIsTheTargetLanguage_knownForm_saysItIsAlreadyThere() {
        // The model checking its own French form: found, with the decided form ("AF" → "AFam") and a note.
        AcronymLookupTool.AcronymLookupResult result =
                tool.lookupAcronyms(List.of("AF"), SourceLanguage.FR, context("fr")).getFirst();

        assertThat(result.found()).isTrue();
        assertThat(result.note()).isEqualTo(AcronymLookupTool.SAME_LANGUAGE_KNOWN);
        assertThat(result.candidates()).extracting(AcronymLookupTool.Candidate::targetForms)
                .containsExactlyInAnyOrder(List.of("AFam"), List.of("AF"));
    }

    @Test
    void lookupAcronyms_sourceIsTheTargetLanguage_unknownForm_saysThereIsNothingToTranslate() {
        AcronymLookupTool.AcronymLookupResult result =
                tool.lookupAcronyms(List.of("XYZ"), SourceLanguage.FR, context("fr")).getFirst();

        assertThat(result.found()).isFalse();
        assertThat(result.candidates()).isEmpty();
        assertThat(result.note()).isEqualTo(AcronymLookupTool.SAME_LANGUAGE_UNKNOWN);
    }

    @Test
    void lookupAcronyms_differentLanguages_hasNoNote() {
        assertThat(tool.lookupAcronyms(List.of("AHV", "XYZ"), SourceLanguage.DE, context("fr")))
                .extracting(AcronymLookupTool.AcronymLookupResult::note)
                .containsOnlyNulls();
    }

    @Test
    void lookupAcronyms_candidateCarriesFormsNamesAndDefinition() {
        AcronymLookupTool.Candidate candidate = tool.lookupAcronyms(List.of("AHV"), SourceLanguage.DE, context("fr"))
                .getFirst().candidates().getFirst();

        assertThat(candidate.conceptId()).isEqualTo("test:ahv");
        assertThat(candidate.targetForms()).containsExactly("AVS");
        assertThat(candidate.targetFormKind()).isEqualTo(Glossary.TargetFormKind.ACRONYM);
        assertThat(candidate.targetFullNames()).containsExactly("assurance-vieillesse et survivants");
        assertThat(candidate.definition()).isEqualTo("Premier pilier.");
    }

    @Test
    void lookupAcronyms_resultIsSentToTheModelAsJson() {
        // Spring AI converts the returned list with this converter before sending it back to the model.
        String json = new DefaultToolCallResultConverter()
                .convert(tool.lookupAcronyms(List.of("BSV"), SourceLanguage.DE, context("fr")), List.class);

        assertThat(json)
                .contains("\"acronym\":\"BSV\"")
                .contains("\"found\":true")
                .contains("\"targetForms\":[\"Office fédéral des assurances sociales\"]")
                .contains("\"targetFormKind\":\"FULL_NAME\"")
                .doesNotContain("\"note\"");
    }

    @Test
    void lookupAcronyms_sameLanguageNoteIsSentToTheModel() {
        String json = new DefaultToolCallResultConverter()
                .convert(tool.lookupAcronyms(List.of("XYZ"), SourceLanguage.FR, context("fr")), List.class);

        assertThat(json).contains("\"note\":\"The text is already in the target language");
    }

    @Test
    void toolDefinition_offersSourceLanguageAsARequiredEnum() throws Exception {
        // The JSON Schema Spring AI sends to the model in the request's "tools" field.
        JsonNode schema = JsonMapper.builder().build().readTree(lookupCallback().getToolDefinition().inputSchema());

        assertThat(schema.at("/properties/sourceLanguage/enum"))
                .extracting(JsonNode::asString)
                .containsExactly("DE", "FR", "IT", "EN");
        assertThat(schema.get("required")).extracting(JsonNode::asString).contains("acronyms", "sourceLanguage");
        assertThat(schema.at("/properties/toolContext").isMissingNode()).isTrue();
    }

    @Test
    void toolCall_convertsTheModelsEnumValue() {
        // Same path as a real tool call: Spring AI parses the model's JSON arguments, then calls the method.
        String result = lookupCallback().call("{\"acronyms\": [\"EPA\"], \"sourceLanguage\": \"DE\"}", context("fr"));

        assertThat(result).contains("test:epa-de").doesNotContain("test:epa-en");
    }

    @Test
    void toolCall_valueOutsideTheEnum_becomesAnErrorMessageForTheModel() {
        // The conversion is exact: "de" is not "DE". Spring AI's default processor sends the error back to the model.
        ToolExecutionException exception = catchThrowableOfType(ToolExecutionException.class,
                () -> lookupCallback().call("{\"acronyms\": [\"EPA\"], \"sourceLanguage\": \"de\"}", context("fr")));

        String messageForTheModel = DefaultToolExecutionExceptionProcessor.builder().build().process(exception);

        assertThat(messageForTheModel).contains("SourceLanguage").contains("\"de\"");
    }

    @Test
    void truncate_longDefinitionsAreCut() {
        String longText = "a".repeat(AcronymLookupTool.MAX_DEFINITION_LENGTH + 50);

        assertThat(AcronymLookupTool.truncate(longText)).hasSize(AcronymLookupTool.MAX_DEFINITION_LENGTH).endsWith("…");
        assertThat(AcronymLookupTool.truncate("short")).isEqualTo("short");
    }

    private static ToolCallback lookupCallback() {
        return ToolCallbacks.from(tool)[0];
    }

    private static ToolContext context(String targetLanguage) {
        return new ToolContext(Map.of(AcronymLookupTool.TARGET_LANGUAGE, targetLanguage));
    }
}
