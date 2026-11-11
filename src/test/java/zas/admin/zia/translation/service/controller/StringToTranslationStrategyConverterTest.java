package zas.admin.zia.translation.service.controller;

import org.junit.jupiter.api.Test;
import zas.admin.zia.translation.service.TranslationStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StringToTranslationStrategyConverterTest {

    private final StringToTranslationStrategyConverter converter = new StringToTranslationStrategyConverter();

    @Test
    void convert_single_returnsCorrectEnum() {
        assertThat(converter.convert("single")).isEqualTo(TranslationStrategy.SINGLE);
    }

    @Test
    void convert_dual_returnsCorrectEnum() {
        assertThat(converter.convert("dual")).isEqualTo(TranslationStrategy.DUAL);
    }

    @Test
    void convert_uppercase_returnsCorrectEnum() {
        assertThat(converter.convert("SINGLE")).isEqualTo(TranslationStrategy.SINGLE);
        assertThat(converter.convert("DUAL")).isEqualTo(TranslationStrategy.DUAL);
    }

    @Test
    void convert_unknownValue_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> converter.convert("fast"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fast");
    }
}
