package zas.admin.zia.translation.service.parser;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageDocumentParserTest {

    private final ImageDocumentParser parser = new ImageDocumentParser();

    @Test
    void supportedMimeTypes_returnsAllImageTypes() {
        assertThat(parser.supportedMimeTypes()).containsExactly(
                "image/png",
                "image/jpeg",
                "image/jpg",
                "image/gif",
                "image/bmp");
    }

    @Test
    void renderPages_validPng_returnsOnePngPage() throws IOException {
        byte[] pngBytes = createImageBytes("png", 32, 16);

        var pages = parser.renderPages(pngBytes);

        assertThat(pages).hasSize(1);
        assertThat(pages.getFirst()).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47);
    }

    @Test
    void renderPages_validJpeg_convertsToPng() throws IOException {
        byte[] jpegBytes = createImageBytes("jpeg", 32, 16);

        var pages = parser.renderPages(jpegBytes);

        assertThat(pages).hasSize(1);
        assertThat(pages.getFirst()).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47);
    }

    @Test
    void renderPages_invalidBytes_throwsIOException() {
        byte[] invalidBytes = {1, 2, 3, 4};

        assertThatThrownBy(() -> parser.renderPages(invalidBytes))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unable to read image");
    }

    @Test
    void extractPageLayouts_returnsCorrectDimensions() throws IOException {
        byte[] pngBytes = createImageBytes("png", 200, 100);

        var layouts = parser.extractPageLayouts(pngBytes);

        assertThat(layouts).containsExactly(new PageLayout(200f, 100f));
    }

    @Test
    void extractPageLayouts_invalidBytes_throwsIOException() {
        byte[] invalidBytes = {9, 8, 7, 6};

        assertThatThrownBy(() -> parser.extractPageLayouts(invalidBytes))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unable to read image");
    }

    private static byte[] createImageBytes(String formatName, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        try (var outputStream = new ByteArrayOutputStream()) {
            boolean written = ImageIO.write(image, formatName, outputStream);
            if (!written) {
                throw new IOException("No suitable ImageIO writer found for format " + formatName);
            }
            return outputStream.toByteArray();
        }
    }
}
