package zas.admin.zia.translation.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TranslationStrategyTest {

    @Test
    void fromString_single_lowercase_returnsCorrectEnum() {
        assertThat(TranslationStrategy.fromString("single")).isEqualTo(TranslationStrategy.SINGLE);
    }

    @Test
    void fromString_dual_lowercase_returnsCorrectEnum() {
        assertThat(TranslationStrategy.fromString("dual")).isEqualTo(TranslationStrategy.DUAL);
    }

    @Test
    void fromString_single_uppercase_returnsCorrectEnum() {
        assertThat(TranslationStrategy.fromString("SINGLE")).isEqualTo(TranslationStrategy.SINGLE);
    }

    @Test
    void fromString_dual_uppercase_returnsCorrectEnum() {
        assertThat(TranslationStrategy.fromString("DUAL")).isEqualTo(TranslationStrategy.DUAL);
    }

    @Test
    void fromString_mixed_case_returnsCorrectEnum() {
        assertThat(TranslationStrategy.fromString("Single")).isEqualTo(TranslationStrategy.SINGLE);
        assertThat(TranslationStrategy.fromString("Dual")).isEqualTo(TranslationStrategy.DUAL);
    }

    @Test
    void fromString_withLeadingTrailingWhitespace_returnsCorrectEnum() {
        assertThat(TranslationStrategy.fromString("  single  ")).isEqualTo(TranslationStrategy.SINGLE);
    }

    @Test
    void fromString_unknownValue_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> TranslationStrategy.fromString("fast"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fast")
                .hasMessageContaining("single")
                .hasMessageContaining("dual");
    }

    @Test
    void fromString_emptyStringAfterTrim_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> TranslationStrategy.fromString("unknown"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
