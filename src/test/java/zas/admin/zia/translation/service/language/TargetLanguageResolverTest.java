package zas.admin.zia.translation.service.language;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetLanguageResolverTest {

    private final TargetLanguageResolver resolver = new TargetLanguageResolver("de");

    @Test
    void resolve_supportedLanguage_returnsIt() {
        assertThat(resolver.resolve("it")).isEqualTo(TargetLanguage.IT);
        assertThat(resolver.resolve("Französisch")).isEqualTo(TargetLanguage.FR);
    }

    @Test
    void resolve_missingOrUnsupportedLanguage_fallsBackToDefault() {
        assertThat(resolver.resolve(null)).isEqualTo(TargetLanguage.DE);
        assertThat(resolver.resolve("")).isEqualTo(TargetLanguage.DE);
        assertThat(resolver.resolve("es")).isEqualTo(TargetLanguage.DE);
        assertThat(resolver.resolve("evil\r\ninjected")).isEqualTo(TargetLanguage.DE);
    }

    @Test
    void constructor_acceptsLanguageNamesAsDefault() {
        assertThat(new TargetLanguageResolver("English").defaultLanguage()).isEqualTo(TargetLanguage.EN);
    }

    @Test
    void constructor_invalidDefaultLanguage_failsFast() {
        assertThatThrownBy(() -> new TargetLanguageResolver("es"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid default target language 'es'");
        assertThatThrownBy(() -> new TargetLanguageResolver(""))
                .isInstanceOf(IllegalStateException.class);
    }
}
