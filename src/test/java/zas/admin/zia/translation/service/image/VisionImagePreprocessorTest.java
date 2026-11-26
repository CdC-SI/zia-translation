package zas.admin.zia.translation.service.image;

import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisionImagePreprocessorTest {

    private static final int BLUE = 0x0000FF;
    private static final int WHITE = 0xFFFFFF;

    private final VisionImagePreprocessor preprocessor = new VisionImagePreprocessor(1_000_000);

    @Test
    void toPng_pageRenderedAtTheBudget_onlyPaddedWithWhite() throws IOException {
        // an A4 page as PdfDocumentParser renders it at 1 MP
        BufferedImage output = decode(preprocessor.toPng(createImage(831, 1176)));

        assertThat(output.getWidth()).isEqualTo(832);
        assertThat(output.getHeight()).isEqualTo(1184);
        // content kept pixel for pixel at the top left, padding on the right and bottom
        assertThat(output.getRGB(0, 0) & WHITE).isEqualTo(BLUE);
        assertThat(output.getRGB(830, 1175) & WHITE).isEqualTo(BLUE);
        assertThat(output.getRGB(831, 600) & WHITE).isEqualTo(WHITE);
        assertThat(output.getRGB(400, 1183) & WHITE).isEqualTo(WHITE);
    }

    @Test
    void toPng_imageAboveTheBudget_shrunkWithItsRatio() throws IOException {
        BufferedImage output = decode(preprocessor.toPng(createImage(4032, 3024)));

        assertThat(output.getWidth()).isEqualTo(1152);
        assertThat(output.getHeight()).isEqualTo(864);
    }

    @Test
    void toPng_smallImage_neverEnlarged() throws IOException {
        BufferedImage output = decode(preprocessor.toPng(createImage(20, 10)));

        assertThat(output.getWidth()).isEqualTo(32);
        assertThat(output.getHeight()).isEqualTo(32);
        assertThat(output.getRGB(19, 9) & WHITE).isEqualTo(BLUE);
        assertThat(output.getRGB(25, 5) & WHITE).isEqualTo(WHITE);
    }

    @Test
    void toPng_imageAlreadyAtMultiplesOf32_keptAsIs() throws IOException {
        BufferedImage source = createImage(64, 96);

        assertThat(preprocessor.prepare(source)).isSameAs(source);
        assertThat(decode(preprocessor.toPng(source)).getWidth()).isEqualTo(64);
    }

    @Test
    void toPng_imageWithAlpha_flattenedOnWhite() throws IOException {
        BufferedImage source = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = source.createGraphics();
        graphics.setComposite(AlphaComposite.Clear);
        graphics.fillRect(0, 0, 64, 64);
        graphics.dispose();

        BufferedImage output = decode(preprocessor.toPng(source));

        assertThat(output.getRGB(32, 32) & WHITE).isEqualTo(WHITE);
    }

    @Test
    void toMedia_returnsPngMedia() throws IOException {
        Media media = preprocessor.toMedia(createImage(100, 100));

        assertThat(media.getMimeType()).isEqualTo(MimeTypeUtils.IMAGE_PNG);
        byte[] bytes = (byte[]) media.getData();
        assertThat(decode(bytes).getWidth()).isEqualTo(128);
    }

    @Test
    void budget_fromTheConfiguredPixels() {
        assertThat(preprocessor.budget()).isEqualTo(new PixelBudget(1_000_000));
    }

    @Test
    void constructor_budgetBelowOneToken_rejected() {
        assertThatThrownBy(() -> new VisionImagePreprocessor(1023)).isInstanceOf(IllegalArgumentException.class);
    }

    private static BufferedImage decode(byte[] png) throws IOException {
        assertThat(png[1]).isEqualTo((byte) 'P');   // PNG signature: 0x89 'P' 'N' 'G'
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static BufferedImage createImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return image;
    }
}
