package zas.admin.zia.translation.service.language;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TargetLanguageTest {

    @ParameterizedTest
    @CsvSource({
            "fr, FR", "FR, FR", "fra, FR", "French, FR", "français, FR", "Francais, FR", "fr-CH, FR", "Französisch, FR",
            "de, DE", "DE, DE", "deu, DE", "ger, DE", "German, DE", "Deutsch, DE", "allemand, DE", "tedesco, DE", "de_CH, DE",
            "it, IT", "ita, IT", "Italian, IT", "italiano, IT", "italien, IT", "Italienisch, IT", "it-CH, IT",
            "en, EN", "eng, EN", "English, EN", "anglais, EN", "Englisch, EN", "inglese, EN", "en-GB, EN", "' en ', EN"
    })
    void fromString_supportedValues_areResolved(String value, TargetLanguage expected) {
        assertThat(TargetLanguage.fromString(value)).contains(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "es", "spanish", "rm", "klingon", "d", "deutschland"})
    void fromString_unsupportedValues_returnEmpty(String value) {
        assertThat(TargetLanguage.fromString(value)).isEmpty();
    }

    @Test
    void promptLabel_isExplicit() {
        assertThat(TargetLanguage.DE.promptLabel()).isEqualTo("German (Deutsch, ISO 639-1 code \"de\")");
        assertThat(TargetLanguage.FR.promptLabel()).isEqualTo("French (français, ISO 639-1 code \"fr\")");
        assertThat(TargetLanguage.IT.promptLabel()).isEqualTo("Italian (italiano, ISO 639-1 code \"it\")");
        assertThat(TargetLanguage.EN.promptLabel()).isEqualTo("English (ISO 639-1 code \"en\")");
    }
}
