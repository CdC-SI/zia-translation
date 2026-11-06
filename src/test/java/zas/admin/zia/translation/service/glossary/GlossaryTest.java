package zas.admin.zia.translation.service.glossary;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlossaryTest {

    private static Glossary glossary;

    @BeforeAll
    static void loadFixture() {
        glossary = new Glossary(JsonMapper.builder().build(), new ClassPathResource("glossary/glossary-fixture.json"));
    }

    @Test
    void load_readsEveryConceptOfTheFile() {
        assertThat(glossary.size()).isEqualTo(10);
    }

    @Test
    void load_otherSchema_fails() {
        ByteArrayResource file = new ByteArrayResource(
                "{\"schema\": \"something-else\", \"concepts\": []}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new Glossary(JsonMapper.builder().build(), file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("translation-glossary-v1");
    }

    @Test
    void lookup_unknownOrBlankAcronym_returnsNothing() {
        assertThat(glossary.lookup("XYZ", "de", "fr")).isEmpty();
        assertThat(glossary.lookup(" ", "de", "fr")).isEmpty();
        assertThat(glossary.lookup(null, "de", "fr")).isEmpty();
    }

    @Test
    void lookup_searchesOnlyTheSourceLanguage() {
        // "EPA" is a federal office in German and an environmental agency in English.
        assertThat(glossary.lookup("EPA", "de", "fr")).extracting(GlossaryConcept::conceptId).containsExactly("test:epa-de");
        assertThat(glossary.lookup("EPA", "en", "fr")).extracting(GlossaryConcept::conceptId).containsExactly("test:epa-en");
    }

    @Test
    void lookup_acronymOnlyInAnotherLanguage_returnsNothing() {
        // "AVS" exists in French and Italian, not in German: no fallback to other languages.
        assertThat(glossary.lookup("AVS", "de", "fr")).isEmpty();
        assertThat(glossary.lookup("AVS", "it", "de")).extracting(GlossaryConcept::conceptId).containsExactly("test:ahv");
    }

    @Test
    void lookup_sourceEqualToTargetOrMissing_returnsNothing() {
        assertThat(glossary.lookup("AI", "fr", "fr")).isEmpty();
        assertThat(glossary.lookup("AHV", null, "fr")).isEmpty();
    }

    @Test
    void lookup_ambiguousAcronym_returnsEveryConcept() {
        assertThat(glossary.lookup("AK", "de", "fr"))
                .extracting(GlossaryConcept::conceptId)
                .containsExactly("test:ak-compensation-office", "test:ak-labour-force");
    }

    @Test
    void lookup_oldFormsAreStillRecognised() {
        // "FZ" and "AF" are no longer written (the export chose "FamZ" / "AFam"), but still understood in a source.
        assertThat(glossary.lookup("FZ", "de", "fr")).extracting(GlossaryConcept::conceptId).containsExactly("test:famz");
        assertThat(glossary.lookup("AF", "fr", "de"))
                .extracting(GlossaryConcept::conceptId)
                .containsExactlyInAnyOrder("test:famz", "test:af-economy");
    }

    @Test
    void conceptsWithAcronym_ignoresTheTarget() {
        assertThat(glossary.conceptsWithAcronym("AVS", "fr")).extracting(GlossaryConcept::conceptId).containsExactly("test:ahv");
        assertThat(glossary.conceptsWithAcronym("AVS", "de")).isEmpty();
        assertThat(glossary.conceptsWithAcronym(null, "fr")).isEmpty();
    }

    @Test
    void targetForms_isTheOneAcronymOfTheLanguage() {
        GlossaryConcept famz = glossary.lookup("FamZ", "de", "fr").getFirst();

        Glossary.TargetForms forms = Glossary.targetForms(famz, "fr");

        assertThat(forms.kind()).isEqualTo(Glossary.TargetFormKind.ACRONYM);
        assertThat(forms.forms()).containsExactly("AFam");
    }

    @Test
    void targetForms_noTargetAcronym_fallsBackToTheFullName() {
        GlossaryConcept bsv = glossary.lookup("BSV", "de", "fr").getFirst();

        Glossary.TargetForms forms = Glossary.targetForms(bsv, "fr");

        assertThat(forms.kind()).isEqualTo(Glossary.TargetFormKind.FULL_NAME);
        assertThat(forms.forms()).containsExactly("Office fédéral des assurances sociales");
    }

    @Test
    void targetForms_noFormInTheTargetLanguage_returnsNone() {
        GlossaryConcept concept = glossary.lookup("ALK", "de", "fr").getFirst();

        assertThat(Glossary.targetForms(concept, "fr").kind()).isEqualTo(Glossary.TargetFormKind.NONE);
        assertThat(Glossary.targetForms(concept, "fr").forms()).isEmpty();
        assertThat(Glossary.targetForms(concept, "it").kind()).isEqualTo(Glossary.TargetFormKind.NONE);
    }

    @Test
    void targetFullNamesAndDefinition_comeFromTheTargetLanguage() {
        GlossaryConcept ahv = glossary.lookup("AHV", "de", "fr").getFirst();

        assertThat(Glossary.targetFullNames(ahv, "fr")).containsExactly("assurance-vieillesse et survivants");
        assertThat(Glossary.definition(ahv, "fr")).contains("Premier pilier.");
        assertThat(Glossary.definition(ahv, "it")).isEmpty();
    }

    @Test
    void languageCode_acceptsCodesRegionsAndNames() {
        assertThat(Glossary.languageCode("fr")).contains("fr");
        assertThat(Glossary.languageCode(" FR-ch ")).contains("fr");
        assertThat(Glossary.languageCode("French")).contains("fr");
        assertThat(Glossary.languageCode("Deutsch")).contains("de");
        assertThat(Glossary.languageCode("italiano")).contains("it");
        assertThat(Glossary.languageCode("es")).isEmpty();
        assertThat(Glossary.languageCode(null)).isEmpty();
    }

    @Test
    void bundledGlossary_hasTheDecidedForms() {
        Glossary bundled = new Glossary(JsonMapper.builder().build(), new ClassPathResource("glossary/glossary_2026-10-20.json"));

        // acronyms_2026-10-20.json's 1,664 concepts minus termdat:2656, whose only "acronym" is a TERMDAT note.
        assertThat(bundled.size()).isEqualTo(1663);
        assertThat(formsFor(bundled, "AHV", "de", "fr")).containsExactly("AVS");
        assertThat(formsFor(bundled, "IV", "de", "fr")).containsExactly("AI");
        assertThat(formsFor(bundled, "EL", "de", "fr")).containsExactly("PC");
        // One form per concept, as decided in glossary_scraping's manual_synonyms.py.
        assertThat(formsFor(bundled, "FamZ", "de", "fr")).containsExactly("AFam");
        assertThat(formsFor(bundled, "EAE", "de", "fr")).containsExactly("AAP");
        assertThat(formsFor(bundled, "Suva", "de", "fr")).containsExactly("Suva");
        // Old forms are still recognised in a source text.
        assertThat(formsFor(bundled, "VSE", "de", "fr")).containsExactly("AAP");
        assertThat(formsFor(bundled, "AF", "fr", "de")).contains("FamZ");
    }

    private static List<String> formsFor(Glossary glossary, String acronym, String sourceLanguage, String targetLanguage) {
        return glossary.lookup(acronym, sourceLanguage, targetLanguage).stream()
                .flatMap(concept -> Glossary.targetForms(concept, targetLanguage).forms().stream())
                .toList();
    }
}
