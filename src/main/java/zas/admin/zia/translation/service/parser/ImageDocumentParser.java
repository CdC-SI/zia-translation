package zas.admin.zia.translation.service.parser;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import zas.admin.zia.translation.service.InvalidDocumentException;
import zas.admin.zia.translation.service.image.ImageScaling;
import zas.admin.zia.translation.service.image.PixelBudget;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.List;

/**
 * Image uploads. Decoders come from the JDK plus TwelveMonkeys (TIFF, WebP), which registers itself with ImageIO.
 * A multi-page TIFF gives one page per frame; other formats give one page (first frame). Frames are decoded on
 * demand, turned upright according to the EXIF orientation (ImageIO ignores it), converted to RGB on white
 * (CMYK, grayscale, indexed, alpha) and shrunk to the pixel budget by area averaging. The output PDF page is A4,
 * portrait or landscape like the upright image.
 */
@Component
class ImageDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(ImageDocumentParser.class);
    private static final int ORIENTATION_NORMAL = 1;

    private static final List<String> SUPPORTED_MIME_TYPES = List.of(
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/bmp",
            "image/tiff",
            "image/webp");

    static {
        ImageIoPlugins.removeJaiImageIoCorePlugins();
    }

    private final long maxPixels;

    ImageDocumentParser(@Value("${zia.translation.image.max-pixels:50000000}") long maxPixels) {
        if (maxPixels < 1) {
            throw new IllegalArgumentException("zia.translation.image.max-pixels must be >= 1");
        }
        this.maxPixels = maxPixels;
    }

    @Override
    public DocumentPages open(byte[] documentBytes) throws IOException {
        Frames frames = Frames.open(documentBytes);
        // EXIF orientation describes the (single) image of a photo; multi-frame TIFFs are scans: left as stored
        int orientation = frames.count() == 1 ? readOrientation(documentBytes) : ORIENTATION_NORMAL;
        return new ImagePages(frames, orientation);
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

    private void checkSize(Frames frames, int index) throws IOException {
        long pixels = (long) frames.reader().getWidth(index) * frames.reader().getHeight(index);
        if (pixels > maxPixels) {
            throw new InvalidDocumentException("Image too large: %d megapixels (maximum %d megapixels)."
                    .formatted(pixels / 1_000_000, maxPixels / 1_000_000));
        }
    }

    /**
     * EXIF orientation (1-8) of the image, {@value #ORIENTATION_NORMAL} when absent, invalid or unreadable: a broken
     * metadata block must not prevent translating the image.
     */
    static int readOrientation(byte[] imageBytes) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(imageBytes), imageBytes.length);
            ExifIFD0Directory exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                int orientation = exif.getInt(ExifIFD0Directory.TAG_ORIENTATION);
                return orientation >= 1 && orientation <= 8 ? orientation : ORIENTATION_NORMAL;
            }
        } catch (ImageProcessingException | MetadataException | IOException | RuntimeException exception) {
            log.debug("EXIF orientation not readable, image used as stored", exception);
        }
        return ORIENTATION_NORMAL;
    }

    /** Orientations 5-8 store the image turned by a quarter: width and height are swapped once upright. */
    private static boolean isQuarterTurn(int orientation) {
        return orientation >= 5;
    }

    /**
     * Draws the decoded image upright (EXIF orientation) onto white RGB, which also converts CMYK, grayscale,
     * indexed and transparent images. Images already upright and RGB are returned as decoded.
     */
    static BufferedImage toUprightRgb(BufferedImage image, int orientation) {
        if (orientation == ORIENTATION_NORMAL && image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        boolean quarterTurn = isQuarterTurn(orientation);
        BufferedImage rgb = new BufferedImage(quarterTurn ? height : width, quarterTurn ? width : height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            // exact quarter turns and mirrors: every pixel lands on a pixel, no interpolation needed
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.drawImage(image, uprightTransform(orientation, width, height), null);
        } finally {
            graphics.dispose();
        }
        return rgb;
    }

    /** Maps the stored pixel (x, y) of a width x height image to its upright position. */
    private static AffineTransform uprightTransform(int orientation, int width, int height) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);        // mirrored horizontally
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);  // rotated 180
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);       // mirrored vertically
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);             // transposed
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);       // turn 90 clockwise
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width);  // transversed
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);        // turn 90 counter-clockwise
            default -> new AffineTransform();
        };
    }

    /**
     * The frames of an image, decoded on demand. The output page is A4 (the translation is reflowed text: the pixel
     * size of a photo says nothing about a printable page), landscape when the upright image is wider than tall.
     */
    private final class ImagePages implements DocumentPages {

        private final Frames frames;
        private final int orientation;

        ImagePages(Frames frames, int orientation) {
            this.frames = frames;
            this.orientation = orientation;
        }

        @Override
        public int pageCount() {
            return frames.count();
        }

        @Override
        public synchronized PageLayout layout(int index) {
            try {
                int width = frames.reader().getWidth(index);
                int height = frames.reader().getHeight(index);
                boolean landscape = isQuarterTurn(orientation) ? height > width : width > height;
                PDRectangle a4 = PDRectangle.A4;
                return landscape
                        ? new PageLayout(a4.getHeight(), a4.getWidth())
                        : new PageLayout(a4.getWidth(), a4.getHeight());
            } catch (IOException exception) {
                throw new UncheckedIOException("Unable to read the size of image frame " + (index + 1), exception);
            }
        }

        @Override
        public synchronized BufferedImage render(int index, PixelBudget budget) throws IOException {
            checkSize(frames, index);
            BufferedImage upright = toUprightRgb(frames.reader().read(index), orientation);
            PixelBudget.Size size = budget.fitImage(upright.getWidth(), upright.getHeight());
            return ImageScaling.downscale(upright, size.width(), size.height());
        }

        @Override
        public synchronized void close() throws IOException {
            frames.close();
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
