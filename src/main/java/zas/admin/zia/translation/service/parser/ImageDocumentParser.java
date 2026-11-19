package zas.admin.zia.translation.service.parser;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import zas.admin.zia.translation.service.InvalidDocumentException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Image uploads. Decoders come from the JDK plus TwelveMonkeys (CMYK JPEG, TIFF, WebP), which registers itself
 * with ImageIO. A multi-page TIFF gives one page per frame; other formats give one page (first frame).
 */
@Component
class ImageDocumentParser implements DocumentParser {

    private static final List<String> SUPPORTED_MIME_TYPES = List.of(
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/bmp",
            "image/tiff",
            "image/webp");

    private final long maxPixels;

    ImageDocumentParser(@Value("${zia.translation.image.max-pixels:50000000}") long maxPixels) {
        if (maxPixels < 1) {
            throw new IllegalArgumentException("zia.translation.image.max-pixels must be >= 1");
        }
        this.maxPixels = maxPixels;
    }

    @Override
    public List<byte[]> renderPages(byte[] documentBytes) throws IOException {
        try (Frames frames = Frames.open(documentBytes)) {
            List<byte[]> pages = new ArrayList<>(frames.count());
            for (int i = 0; i < frames.count(); i++) {
                checkSize(frames, i);
                pages.add(encodePng(toRgb(frames.reader().read(i))));
            }
            return pages;
        }
    }

    @Override
    public List<String> supportedMimeTypes() {
        return SUPPORTED_MIME_TYPES;
    }

    /**
     * Rejects images whose pixel count exceeds the limit, from the header only (nothing is decoded), so that
     * a small file cannot expand into gigabytes of pixels. Unreadable images are left to fail in rendering.
     */
    @Override
    public void validate(byte[] documentBytes) {
        try (Frames frames = Frames.open(documentBytes)) {
            for (int i = 0; i < frames.count(); i++) {
                checkSize(frames, i);
            }
        } catch (IOException unreadable) {
            // not decided here: rendering reports unreadable images
        }
    }

    @Override
    public List<PageLayout> extractPageLayouts(byte[] documentBytes) throws IOException {
        try (Frames frames = Frames.open(documentBytes)) {
            List<PageLayout> layouts = new ArrayList<>(frames.count());
            for (int i = 0; i < frames.count(); i++) {
                layouts.add(new PageLayout(frames.reader().getWidth(i), frames.reader().getHeight(i)));
            }
            return layouts;
        }
    }

    private void checkSize(Frames frames, int index) throws IOException {
        long pixels = (long) frames.reader().getWidth(index) * frames.reader().getHeight(index);
        if (pixels > maxPixels) {
            throw new InvalidDocumentException("Image too large: %d megapixels (maximum %d megapixels)."
                    .formatted(pixels / 1_000_000, maxPixels / 1_000_000));
        }
    }

    /** Draws any decoded image (CMYK converted by the decoder, grayscale, indexed, with alpha) onto white RGB. */
    private static BufferedImage toRgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rgb;
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        try (var outputStream = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", outputStream)) {
                throw new IOException("No suitable ImageIO writer found for PNG format.");
            }
            return outputStream.toByteArray();
        }
    }

    /** An open ImageIO reader on the image, with the number of frames to process. */
    private record Frames(ImageInputStream input, ImageReader reader, int count) implements AutoCloseable {

        static Frames open(byte[] imageBytes) throws IOException {
            ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes));
            if (input == null) {
                throw new IOException("Unable to read image: no ImageIO input stream available.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                input.close();
                throw new IOException("Unable to read image: format not recognized or data is corrupted.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                int count = isTiff(reader) ? reader.getNumImages(true) : 1;
                if (count < 1) {
                    throw new IOException("Unable to read image: no image found.");
                }
                return new Frames(input, reader, count);
            } catch (IOException | RuntimeException exception) {
                reader.dispose();
                input.close();
                throw exception instanceof IOException io ? io
                        : new IOException("Unable to read image: data is corrupted.", exception);
            }
        }

        private static boolean isTiff(ImageReader reader) throws IOException {
            String format = reader.getFormatName();
            return "tiff".equalsIgnoreCase(format) || "tif".equalsIgnoreCase(format);
        }

        @Override
        public void close() throws IOException {
            reader.dispose();
            input.close();
        }
    }
}
