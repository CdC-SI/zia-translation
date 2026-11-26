package zas.admin.zia.translation.service.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PixelBudgetTest {

    private static final float A4_WIDTH_PT = 595.28f;
    private static final float A4_HEIGHT_PT = 841.89f;

    @ParameterizedTest
    @CsvSource({
            // budget,  content,     padded (sent image)
            "1000000,   832, 1178,   832, 1184",   // A4 at ~100 DPI, 962 tokens
            "1310720,   951, 1344,   960, 1344",   // the former server cap, 1260 tokens
            "2101248,  1216, 1721,  1216, 1728",   // extraction, A4 at ~147 DPI
            "2359296,  1280, 1811,  1280, 1824",
    })
    void fitPage_a4_fillsTheBudgetOncePadded(long budget, int width, int height, int paddedWidth, int paddedHeight) {
        PixelBudget.Size size = new PixelBudget(budget).fitPage(A4_WIDTH_PT, A4_HEIGHT_PT);

        assertThat(size.width()).isEqualTo(width);
        assertThat(size.height()).isEqualTo(height);
        assertThat(size.paddedWidth()).isEqualTo(paddedWidth);
        assertThat(size.paddedHeight()).isEqualTo(paddedHeight);
        assertThat((long) paddedWidth * paddedHeight).isLessThanOrEqualTo(budget);
    }

    @Test
    void fitPage_landscapeA4_swapsTheSides() {
        PixelBudget.Size size = new PixelBudget(1_000_000).fitPage(A4_HEIGHT_PT, A4_WIDTH_PT);

        assertThat(size.width()).isEqualTo(1178);
        assertThat(size.height()).isEqualTo(832);
    }

    @Test
    void fitPage_scaleIsPixelsPerPoint() {
        PixelBudget.Size size = new PixelBudget(1_000_000).fitPage(A4_WIDTH_PT, A4_HEIGHT_PT);

        assertThat(size.scale() * 72).isCloseTo(100.7, org.assertj.core.data.Offset.offset(0.1));   // DPI
    }

    @Test
    void fitPage_smallPage_isEnlarged() {
        // receipt 80 x 200 mm: rendered at ~195 DPI to use the budget
        PixelBudget.Size size = new PixelBudget(1_000_000).fitPage(226.8, 566.9);

        assertThat(size.scale() * 72).isGreaterThan(190);
    }

    @Test
    void fitImage_phonePhoto_keepsTheExactRatioWithoutPadding() {
        PixelBudget.Size size = new PixelBudget(1_000_000).fitImage(3024, 4032);

        assertThat(size.width()).isEqualTo(864);
        assertThat(size.height()).isEqualTo(1152);
        assertThat(size.paddedWidth()).isEqualTo(864);
        assertThat(size.paddedHeight()).isEqualTo(1152);
    }

    @Test
    void fitImage_smallImage_isNeverEnlarged() {
        PixelBudget.Size size = new PixelBudget(1_000_000).fitImage(500, 400);

        assertThat(size.width()).isEqualTo(500);
        assertThat(size.height()).isEqualTo(400);
        assertThat(size.scale()).isEqualTo(1.0);
        assertThat(size.paddedWidth()).isEqualTo(512);
        assertThat(size.paddedHeight()).isEqualTo(416);
    }

    @Test
    void fitImage_alreadyFittedImage_isUnchanged() {
        // a page rendered at the budget comes back to the preprocessor: it must only be padded
        PixelBudget budget = new PixelBudget(1_000_000);
        PixelBudget.Size size = budget.fitImage(831, 1176);

        assertThat(size.width()).isEqualTo(831);
        assertThat(size.height()).isEqualTo(1176);
    }

    @Test
    void fitImage_veryThinImage_endsWithinTheBudget() {
        PixelBudget budget = new PixelBudget(1024);
        PixelBudget.Size size = budget.fitImage(100_000, 3);

        assertThat((long) size.paddedWidth() * size.paddedHeight()).isLessThanOrEqualTo(1024);
        assertThat(size.height()).isEqualTo(1);
    }

    @Test
    void fit_randomSizes_withinBudgetAndRatioKept() {
        Random random = new Random(42);
        for (int i = 0; i < 2000; i++) {
            long budget = 1024 + random.nextInt(3_000_000);
            int width = 33 + random.nextInt(8000);
            int height = 33 + random.nextInt(8000);
            PixelBudget pixelBudget = new PixelBudget(budget);

            for (PixelBudget.Size size : new PixelBudget.Size[]{
                    pixelBudget.fitImage(width, height), pixelBudget.fitPage(width, height)}) {
                assertThat((long) size.paddedWidth() * size.paddedHeight()).isLessThanOrEqualTo(budget);
                assertThat(size.paddedWidth() % 32).isZero();
                assertThat(size.paddedHeight() % 32).isZero();
                // both sides scaled by the same factor, each rounded down: less than 1 px off the exact ratio
                if (size.width() > 1 && size.height() > 1) {
                    assertThat(Math.abs(size.width() - width * size.scale())).isLessThan(1.0);
                    assertThat(Math.abs(size.height() - height * size.scale())).isLessThan(1.0);
                }
            }
            assertThat(pixelBudget.fitImage(width, height).scale()).isLessThanOrEqualTo(1.0);
        }
    }

    @Test
    void fits_comparesThePaddedSize() {
        PixelBudget budget = new PixelBudget(832 * 1184);

        assertThat(budget.fits(832, 1177)).isTrue();
        assertThat(budget.fits(833, 1177)).isFalse();
    }

    @Test
    void constructor_belowOneToken_rejected() {
        assertThatThrownBy(() -> new PixelBudget(1023)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fitImage_emptySize_rejected() {
        PixelBudget budget = new PixelBudget(1_000_000);

        assertThatThrownBy(() -> budget.fitImage(0, 10)).isInstanceOf(IllegalArgumentException.class);
    }
}
