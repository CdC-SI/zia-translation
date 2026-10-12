package zas.admin.zia.translation.service.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisionImagePreprocessorTest {

    private final VisionImagePreprocessor preprocessor = new VisionImagePreprocessor(1024, 0.75f);

    @ParameterizedTest
    @CsvSource({
            "723.9, 704",
            "1024, 1024",
            "1023.9999999999, 1024",
            "600, 576",
            "20, 32",
    })
    void roundDownToMultiple_roundsDownToNearestMultipleOf32(double input, int expected) {
        assertThat(VisionImagePreprocessor.roundDownToMultiple(input)).isEqualTo(expected);
    }

    @Test
    void preprocess_a4At150Dpi_resizesPreservingAspectRatio() throws IOException {
        assertDimensions(1240, 1754, 704, 1024);
    }

    @Test
    void preprocess_smartphonePhoto_resizesToMaxEdge() throws IOException {
        assertDimensions(4032, 3024, 1024, 768);
    }

    @Test
    void preprocess_widePanorama_resizesToMaxEdge() throws IOException {
        assertDimensions(2000, 500, 1024, 256);
    }

    @Test
    void preprocess_imageSmallerThanMaxEdge_roundsDownWithoutEnlarging() throws IOException {
        assertDimensions(800, 600, 800, 576);
    }

    @Test
    void preprocess_imageAtMaxEdge_keepsSameDimensions() throws IOException {
        assertDimensions(1024, 1024, 1024, 1024);
    }

    @Test
    void preprocess_tinyImage_isFlooredToMinimum32() throws IOException {
        assertDimensions(20, 10, 32, 32);
    }

    @Test
    void preprocess_imageWithAlpha_isFlattenedOnWhiteBackground() throws IOException {
        BufferedImage source = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = source.createGraphics();
        graphics.setComposite(java.awt.AlphaComposite.Clear);
        graphics.fillRect(0, 0, 64, 64);
        graphics.dispose();

        VisionImagePreprocessor.PreprocessedImage result = preprocessor.preprocess(toPng(source));
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(result.bytes()));

        int rgb = output.getRGB(output.getWidth() / 2, output.getHeight() / 2);
        Color pixel = new Color(rgb);
        assertThat(pixel.getRed()).isGreaterThan(240);
        assertThat(pixel.getGreen()).isGreaterThan(240);
        assertThat(pixel.getBlue()).isGreaterThan(240);
    }

    @Test
    void preprocess_outputIsValidJpeg() throws IOException {
        BufferedImage source = createImage(100, 100);
        VisionImagePreprocessor.PreprocessedImage result = preprocessor.preprocess(toPng(source));

        byte[] bytes = result.bytes();
        assertThat(bytes.length).isGreaterThan(3);
        assertThat(bytes[0]).isEqualTo((byte) 0xFF);
        assertThat(bytes[1]).isEqualTo((byte) 0xD8);
        assertThat(bytes[2]).isEqualTo((byte) 0xFF);
        assertThat(result.mimeType()).isEqualTo(MimeTypeUtils.IMAGE_JPEG);
    }

    @Test
    void preprocess_lowerQualityProducesSmallerOutput() throws IOException {
        BufferedImage source = createImage(512, 512);
        byte[] png = toPng(source);

        VisionImagePreprocessor highQuality = new VisionImagePreprocessor(1024, 1.0f);
        VisionImagePreprocessor lowQuality = new VisionImagePreprocessor(1024, 0.75f);

        int highQualitySize = highQuality.preprocess(png).bytes().length;
        int lowQualitySize = lowQuality.preprocess(png).bytes().length;

        assertThat(lowQualitySize).isLessThan(highQualitySize);
    }

    @Test
    void preprocess_invalidBytes_throwsImagePreprocessingException() {
        assertThatThrownBy(() -> preprocessor.preprocess(new byte[]{1, 2, 3}))
                .isInstanceOf(ImagePreprocessingException.class);
    }

    @Test
    void toMedia_returnsJpegMediaWithPreprocessedBytes() throws IOException {
        BufferedImage source = createImage(100, 100);
        Media media = preprocessor.toMedia(toPng(source));

        assertThat(media.getMimeType()).isEqualTo(MimeTypeUtils.IMAGE_JPEG);
    }

    @Test
    void constructor_maxEdgeBelowMinimum_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new VisionImagePreprocessor(31, 0.75f))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_jpegQualityZero_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new VisionImagePreprocessor(1024, 0.0f))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_jpegQualityAboveOne_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new VisionImagePreprocessor(1024, 1.1f))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_jpegQualityNaN_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new VisionImagePreprocessor(1024, Float.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertDimensions(int sourceWidth, int sourceHeight, int expectedWidth, int expectedHeight) throws IOException {
        BufferedImage source = createImage(sourceWidth, sourceHeight);
        VisionImagePreprocessor.PreprocessedImage result = preprocessor.preprocess(toPng(source));
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(result.bytes()));

        assertThat(output.getWidth()).isEqualTo(expectedWidth);
        assertThat(output.getHeight()).isEqualTo(expectedHeight);
        assertThat(result.mimeType()).isEqualTo(MimeTypeUtils.IMAGE_JPEG);
    }

    private static BufferedImage createImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return image;
    }

    private static byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);
        return outputStream.toByteArray();
    }
}
