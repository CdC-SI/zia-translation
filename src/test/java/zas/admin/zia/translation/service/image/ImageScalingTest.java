package zas.admin.zia.translation.service.image;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class ImageScalingTest {

    private static final int SOURCE_WIDTH = 3024;
    private static final int SOURCE_HEIGHT = 4032;
    private static final int TARGET_WIDTH = 864;
    private static final int TARGET_HEIGHT = 1152;
    private static final int LINE_SPACING = 37;

    @Test
    void downscale_phonePhoto_keepsEveryThinLine() {
        BufferedImage scaled = ImageScaling.downscale(thinLines(), TARGET_WIDTH, TARGET_HEIGHT);

        assertThat(scaled.getWidth()).isEqualTo(TARGET_WIDTH);
        assertThat(scaled.getHeight()).isEqualTo(TARGET_HEIGHT);
        assertThat(scaled.getType()).isEqualTo(BufferedImage.TYPE_INT_RGB);
        assertThat(missingLines(scaled)).isZero();
    }

    @Test
    void oneStepBicubic_losesThinLines() {
        // what the preprocessor did before: shows what the area averaging fixes
        BufferedImage bicubic = new BufferedImage(TARGET_WIDTH, TARGET_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = bicubic.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(thinLines(), 0, 0, TARGET_WIDTH, TARGET_HEIGHT, null);
        graphics.dispose();

        assertThat(missingLines(bicubic)).isGreaterThan(10);
    }

    @Test
    void downscale_sameSize_returnsTheImage() {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);

        assertThat(ImageScaling.downscale(image, 64, 32)).isSameAs(image);
    }

    @Test
    void downscale_transparentImage_flattenedOnWhite() {
        BufferedImage transparent = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);

        BufferedImage scaled = ImageScaling.downscale(transparent, 32, 32);

        assertThat(scaled.getRGB(16, 16) & 0xFFFFFF).isEqualTo(0xFFFFFF);
    }

    /** White photo-sized image with a 1 px black horizontal line every {@value #LINE_SPACING} px. */
    private static BufferedImage thinLines() {
        BufferedImage image = new BufferedImage(SOURCE_WIDTH, SOURCE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, SOURCE_WIDTH, SOURCE_HEIGHT);
        graphics.setColor(Color.BLACK);
        for (int y = LINE_SPACING; y < SOURCE_HEIGHT - LINE_SPACING; y += LINE_SPACING) {
            graphics.fillRect(0, y, SOURCE_WIDTH, 1);
        }
        graphics.dispose();
        return image;
    }

    /**
     * Lines with no visible trace near their scaled row. A 1 px line shrunk 3.5 times is ideally a grey of about 180
     * to 220 (a 0.29 px line spread over one pixel): darker than 230 counts as visible.
     */
    private static int missingLines(BufferedImage scaled) {
        int missing = 0;
        for (int y = LINE_SPACING; y < SOURCE_HEIGHT - LINE_SPACING; y += LINE_SPACING) {
            int row = (int) Math.round(y * (double) TARGET_HEIGHT / SOURCE_HEIGHT);
            int darkest = 255;
            for (int r = Math.max(0, row - 2); r <= Math.min(TARGET_HEIGHT - 1, row + 2); r++) {
                darkest = Math.min(darkest, scaled.getRGB(TARGET_WIDTH / 2, r) & 0xFF);
            }
            if (darkest >= 230) {
                missing++;
            }
        }
        return missing;
    }
}
