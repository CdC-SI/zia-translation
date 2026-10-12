package zas.admin.zia.translation.service.glossary;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GlossaryTest {

    private static Glossary glossary;

    @BeforeAll
    static void loadFixture() {
        glossary = new Glossary(new ObjectMapper(), new ClassPathResource("glossary/glossary-fixture.json"));
    }

    @Test
    void load_keepsOnlyConceptsWithAnAcronym() {
        assertThat(glossary.size()).isEqualTo(8);
        assertThat(glossary.lookup("Rente", "de", "fr")).isEmpty();
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
    void targetForms_acronymsOfUnflaggedEntries_deduplicated() {
        GlossaryConcept ahv = glossary.lookup("AHV", "de", "fr").getFirst();

        Glossary.TargetForms forms = Glossary.targetForms(ahv, "fr");

        assertThat(forms.kind()).isEqualTo(Glossary.TargetFormKind.ACRONYM);
        assertThat(forms.forms()).containsExactly("AVS");
    }

    @Test
    void targetForms_noTargetAcronym_fallsBackToFullNames() {
        GlossaryConcept bsv = glossary.lookup("BSV", "de", "fr").getFirst();

        Glossary.TargetForms forms = Glossary.targetForms(bsv, "fr");

        assertThat(forms.kind()).isEqualTo(Glossary.TargetFormKind.FULL_NAME);
        assertThat(forms.forms()).containsExactly("Office fédéral des assurances sociales");
    }

    @Test
    void targetForms_onlyFlaggedTargetEntries_returnsNone() {
        GlossaryConcept concept = glossary.lookup("ALK", "de", "fr").getFirst();

        Glossary.TargetForms forms = Glossary.targetForms(concept, "fr");

        assertThat(forms.kind()).isEqualTo(Glossary.TargetFormKind.NONE);
        assertThat(forms.forms()).isEmpty();
    }

    @Test
    void definition_skipsBlankDefinitionsAndPrefersUnflaggedEntries() {
        GlossaryConcept ahv = glossary.lookup("AHV", "de", "fr").getFirst();

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
    void bundledGlossary_matchesTheEvaluationGlossary() {
        Glossary bundled = new Glossary(new ObjectMapper(), new ClassPathResource("glossary/taxonomy_2026-10-01.json"));

        // Same concept count as acronyms_2026-10-01.json, the glossary the gold sets are built on.
        assertThat(bundled.size()).isEqualTo(1664);
        assertThat(formsFor(bundled, "AHV", "fr")).containsExactly("AVS");
        assertThat(formsFor(bundled, "IV", "fr")).containsExactly("AI");
        assertThat(formsFor(bundled, "EL", "fr")).containsExactly("PC");
    }

    private static List<String> formsFor(Glossary glossary, String acronym, String targetLanguage) {
        return glossary.lookup(acronym, "de", targetLanguage).stream()
                .flatMap(concept -> Glossary.targetForms(concept, targetLanguage).forms().stream())
                .toList();
    }
}
