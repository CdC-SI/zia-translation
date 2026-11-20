package zas.admin.zia.translation.service.parser;

import org.junit.jupiter.api.Test;
import zas.admin.zia.translation.service.InvalidDocumentException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageDocumentParserTest {

    /** 1x1 lossy WebP (the well-known browser feature-detection sample). */
    private static final byte[] WEBP_1X1 = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");

    private final ImageDocumentParser parser = new ImageDocumentParser(50_000_000);

    @Test
    void supportedMimeTypes_returnsAllImageTypes() {
        assertThat(parser.supportedMimeTypes()).containsExactly(
                "image/png",
                "image/jpeg",
                "image/gif",
                "image/bmp",
                "image/tiff",
                "image/webp");
    }

    @Test
    void open_png_onePageRenderedAsRgb() throws IOException {
        try (DocumentPages pages = parser.open(createImageBytes("png", 32, 16))) {
            assertThat(pages.pageCount()).isEqualTo(1);
            BufferedImage page = pages.render(0);
            assertThat(page.getType()).isEqualTo(BufferedImage.TYPE_INT_RGB);
            assertThat(page.getWidth()).isEqualTo(32);
            assertThat(page.getHeight()).isEqualTo(16);
        }
    }

    @Test
    void open_jpeg_onePage() throws IOException {
        assertThat(renderAll(createImageBytes("jpeg", 32, 16))).hasSize(1);
    }

    @Test
    void open_invalidBytes_throwsIOException() {
        assertThatThrownBy(() -> parser.open(new byte[]{1, 2, 3, 4}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unable to read image");
    }

    @Test
    void layout_returnsPixelDimensions() throws IOException {
        try (DocumentPages pages = parser.open(createImageBytes("png", 200, 100))) {
            assertThat(pages.layout(0)).isEqualTo(new PageLayout(200f, 100f));
        }
    }

    @Test
    void render_multiPageTiff_returnsOnePagePerFrame() throws IOException {
        byte[] tiff = createMultiPageTiff(3, 40, 20);

        assertThat(renderAll(tiff)).hasSize(3);
        try (DocumentPages pages = parser.open(tiff)) {
            assertThat(List.of(pages.layout(0), pages.layout(1), pages.layout(2))).containsOnly(new PageLayout(40f, 20f));
        }
    }

    @Test
    void render_webp_returnsOnePngPage() throws IOException {
        var pages = renderAll(WEBP_1X1);

        assertThat(pages).hasSize(1);
        assertThat(pages.getFirst().getWidth()).isEqualTo(1);
    }

    @Test
    void render_cmykJpeg_convertedToRgbWithCorrectColours() throws IOException {
        // Adobe CMYK JPEG (APP14, inverted values) of pure red, as written by scanners/Photoshop:
        // the JDK decodes it, but could not encode the CMYK result as PNG before the RGB conversion
        byte[] cmyk;
        try (InputStream in = getClass().getResourceAsStream("/images/cmyk-red.jpg")) {
            cmyk = in.readAllBytes();
        }

        int rgb = renderAll(cmyk).getFirst().getRGB(8, 8);
        assertThat((rgb >> 16) & 0xFF).isGreaterThan(230);
        assertThat((rgb >> 8) & 0xFF).isLessThan(25);
        assertThat(rgb & 0xFF).isLessThan(25);
    }

    @Test
    void render_transparentPng_flattenedOnWhite() throws IOException {
        BufferedImage transparent = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        byte[] png;
        try (var out = new ByteArrayOutputStream()) {
            ImageIO.write(transparent, "png", out);
            png = out.toByteArray();
        }

        assertThat(renderAll(png).getFirst().getRGB(1, 1) & 0xFFFFFF).isEqualTo(0xFFFFFF);
    }

    @Test
    void validate_imageAboveThePixelLimit_rejectedFromTheHeaderOnly() throws IOException {
        byte[] bomb = pngWithDeclaredSize(100_000, 100_000);   // a few bytes on disk, 10 gigapixels once decoded

        assertThatThrownBy(() -> parser.validate(bomb))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("Image too large: 10000 megapixels (maximum 50 megapixels)");
        assertThatThrownBy(() -> renderAll(bomb)).isInstanceOf(InvalidDocumentException.class);
    }

    @Test
    void validate_normalImage_accepted() throws IOException {
        parser.validate(createImageBytes("png", 200, 100));
    }

    @Test
    void validate_unreadableBytes_leftToRendering() {
        parser.validate(new byte[]{1, 2, 3, 4});
    }

    @Test
    void constructor_rejectsInvalidPixelLimit() {
        assertThatThrownBy(() -> new ImageDocumentParser(0)).isInstanceOf(IllegalArgumentException.class);
    }

    private List<BufferedImage> renderAll(byte[] bytes) throws IOException {
        try (DocumentPages pages = parser.open(bytes)) {
            List<BufferedImage> images = new ArrayList<>();
            for (int i = 0; i < pages.pageCount(); i++) {
                images.add(pages.render(i));
            }
            return images;
        }
    }

    /** A valid 1x1 PNG whose IHDR header is rewritten to declare another size (CRC recomputed). */
    private static byte[] pngWithDeclaredSize(int width, int height) throws IOException {
        byte[] png = createImageBytes("png", 1, 1);
        ByteBuffer buffer = ByteBuffer.wrap(png);
        buffer.putInt(16, width).putInt(20, height);   // IHDR data starts at byte 16
        CRC32 crc = new CRC32();
        crc.update(png, 12, 17);                        // chunk type + 13 data bytes
        buffer.putInt(29, (int) crc.getValue());
        return png;
    }

    private static byte[] createMultiPageTiff(int frames, int width, int height) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("tiff").next();
        try (var out = new ByteArrayOutputStream(); ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            writer.prepareWriteSequence(null);
            for (int i = 0; i < frames; i++) {
                writer.writeToSequence(new IIOImage(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), null, null), param);
            }
            writer.endWriteSequence();
            ios.flush();
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
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
