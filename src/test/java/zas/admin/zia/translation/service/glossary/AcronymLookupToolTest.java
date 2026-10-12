package zas.admin.zia.translation.service.glossary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class AcronymLookupToolTest {

    private static AcronymLookupTool tool;

    @BeforeAll
    static void setUp() {
        tool = new AcronymLookupTool(new Glossary(new ObjectMapper(), new ClassPathResource("glossary/glossary-fixture.json")));
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
    void lookupAcronyms_withoutSourceLanguage_findsNothing() {
        assertThat(tool.lookupAcronyms(List.of("AHV", "IV"), null, context("fr")))
                .extracting(AcronymLookupTool.AcronymLookupResult::found)
                .containsExactly(false, false);
    }

    @Test
    void lookupAcronyms_nullList_returnsEmpty() {
        assertThat(tool.lookupAcronyms(null, SourceLanguage.DE, context("fr"))).isEmpty();
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
                .contains("\"targetFormKind\":\"FULL_NAME\"");
    }

    @Test
    void toolDefinition_offersSourceLanguageAsARequiredEnum() throws Exception {
        // The JSON Schema Spring AI sends to the model in the request's "tools" field.
        JsonNode schema = new ObjectMapper().readTree(lookupCallback().getToolDefinition().inputSchema());

        assertThat(schema.at("/properties/sourceLanguage/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("DE", "FR", "IT", "EN");
        assertThat(schema.get("required")).extracting(JsonNode::asText).contains("acronyms", "sourceLanguage");
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
        // Enum.valueOf is exact: "de" is not "DE". Spring AI's default processor sends the error back to the model.
        ToolExecutionException exception = catchThrowableOfType(ToolExecutionException.class,
                () -> lookupCallback().call("{\"acronyms\": [\"EPA\"], \"sourceLanguage\": \"de\"}", context("fr")));

        String messageForTheModel = DefaultToolExecutionExceptionProcessor.builder().build().process(exception);

        assertThat(messageForTheModel).contains("SourceLanguage.de");
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
