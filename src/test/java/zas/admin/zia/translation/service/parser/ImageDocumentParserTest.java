package zas.admin.zia.translation.service.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import zas.admin.zia.translation.service.InvalidDocumentException;
import zas.admin.zia.translation.service.image.PixelBudget;

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

    private static final PixelBudget BUDGET = new PixelBudget(1_000_000);

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
            BufferedImage page = pages.render(0, BUDGET);
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

    // --- output page: A4, oriented like the upright image (pixels are not points) ---

    private static final PageLayout A4_PORTRAIT = new PageLayout(595.27563f, 841.8898f);
    private static final PageLayout A4_LANDSCAPE = new PageLayout(841.8898f, 595.27563f);

    @Test
    void layout_landscapePhoto_a4Landscape() throws IOException {
        // same 4:3 ratio as a 4032x3024 phone photo, which used to give a 142 x 107 cm page
        assertThat(layoutOf(createImageBytes("jpeg", 403, 302))).isEqualTo(A4_LANDSCAPE);
    }

    @Test
    void layout_portraitPhoto_a4Portrait() throws IOException {
        assertThat(layoutOf(createImageBytes("jpeg", 302, 403))).isEqualTo(A4_PORTRAIT);
        assertThat(layoutOf(createImageBytes("png", 100, 100))).isEqualTo(A4_PORTRAIT);   // square: portrait
    }

    @Test
    void layout_photoStoredLandscapeWithOrientation6_a4Portrait() throws IOException {
        // phones store a portrait shot as a landscape image + "rotate 90" in EXIF
        assertThat(layoutOf(withExifOrientation(createImageBytes("jpeg", 403, 302), 6))).isEqualTo(A4_PORTRAIT);
        assertThat(layoutOf(withExifOrientation(createImageBytes("jpeg", 403, 302), 3))).isEqualTo(A4_LANDSCAPE);
    }

    // --- EXIF orientation: the stored image has a red block in its top-left corner ---

    @ParameterizedTest(name = "orientation {0} -> {1}x{2}, red corner {3}")
    @CsvSource({
            "1, 80, 40, top-left",
            "2, 80, 40, top-right",
            "3, 80, 40, bottom-right",
            "4, 80, 40, bottom-left",
            "5, 40, 80, top-left",
            "6, 40, 80, top-right",
            "7, 40, 80, bottom-right",
            "8, 40, 80, bottom-left"})
    void render_appliesTheExifOrientation(int orientation, int width, int height, String redCorner) throws IOException {
        byte[] jpeg = withExifOrientation(markedImage(), orientation);

        BufferedImage page = renderAll(jpeg).getFirst();

        assertThat(page.getWidth()).isEqualTo(width);
        assertThat(page.getHeight()).isEqualTo(height);
        for (String corner : List.of("top-left", "top-right", "bottom-left", "bottom-right")) {
            int rgb = cornerPixel(page, corner);
            boolean red = ((rgb >> 16) & 0xFF) > 180 && ((rgb >> 8) & 0xFF) < 90 && (rgb & 0xFF) < 90;
            assertThat(red).as("corner %s red", corner).isEqualTo(corner.equals(redCorner));
        }
    }

    @Test
    void readOrientation_noOrUnreadableExif_normal() throws IOException {
        assertThat(ImageDocumentParser.readOrientation(createImageBytes("jpeg", 10, 10))).isEqualTo(1);
        assertThat(ImageDocumentParser.readOrientation(createImageBytes("png", 10, 10))).isEqualTo(1);
        assertThat(ImageDocumentParser.readOrientation(new byte[]{1, 2, 3})).isEqualTo(1);
        assertThat(ImageDocumentParser.readOrientation(withExifOrientation(createImageBytes("jpeg", 10, 10), 9))).isEqualTo(1);
    }

    @Test
    void render_imageWithoutExif_returnedAsDecoded() throws IOException {
        BufferedImage stored = new BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB);

        assertThat(ImageDocumentParser.toUprightRgb(stored, 1)).isSameAs(stored);
    }

    @Test
    void render_phonePhoto_shrunkToTheBudgetWithItsRatio() throws IOException {
        BufferedImage page = renderAll(createImageBytes("jpeg", 3024, 4032)).getFirst();

        assertThat(page.getWidth()).isEqualTo(864);
        assertThat(page.getHeight()).isEqualTo(1152);
        assertThat(page.getType()).isEqualTo(BufferedImage.TYPE_INT_RGB);
    }

    @Test
    void render_multiPageTiff_returnsOnePagePerFrame() throws IOException {
        byte[] tiff = createMultiPageTiff(3, 40, 20);

        assertThat(renderAll(tiff)).hasSize(3);
        try (DocumentPages pages = parser.open(tiff)) {
            assertThat(List.of(pages.layout(0), pages.layout(1), pages.layout(2))).containsOnly(A4_LANDSCAPE);   // 40x20 frames
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

    private PageLayout layoutOf(byte[] bytes) throws IOException {
        try (DocumentPages pages = parser.open(bytes)) {
            return pages.layout(0);
        }
    }

    /** 80x40 white JPEG with a 40x20 red block in its top-left quarter. */
    private static byte[] markedImage() throws IOException {
        BufferedImage image = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 80; x++) {
                image.setRGB(x, y, x < 40 && y < 20 ? 0xFF0000 : 0xFFFFFF);
            }
        }
        try (var out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "jpeg", out);
            return out.toByteArray();
        }
    }

    private static int cornerPixel(BufferedImage image, String corner) {
        int x = corner.endsWith("left") ? 3 : image.getWidth() - 4;
        int y = corner.startsWith("top") ? 3 : image.getHeight() - 4;
        return image.getRGB(x, y);
    }

    /**
     * Inserts an EXIF APP1 segment holding only the Orientation tag after the JFIF APP0 segment, as cameras do.
     */
    private static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        byte[] payload = {
                'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0, 42, 0, 0, 0, 8,                  // big-endian TIFF header, IFD0 at offset 8
                0, 1,                                         // one entry
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0,   // 0x0112 Orientation, SHORT, 1 value
                0, 0, 0, 0};                                  // no next IFD
        int insertAt = 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));   // after SOI + APP0
        ByteBuffer out = ByteBuffer.allocate(jpeg.length + 4 + payload.length);
        out.put(jpeg, 0, insertAt);
        out.put((byte) 0xFF).put((byte) 0xE1).putShort((short) (payload.length + 2)).put(payload);
        out.put(jpeg, insertAt, jpeg.length - insertAt);
        return out.array();
    }

    private List<BufferedImage> renderAll(byte[] bytes) throws IOException {
        try (DocumentPages pages = parser.open(bytes)) {
            List<BufferedImage> images = new ArrayList<>();
            for (int i = 0; i < pages.pageCount(); i++) {
                images.add(pages.render(i, BUDGET));
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
