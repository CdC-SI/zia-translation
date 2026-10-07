package zas.admin.zia.translation.service.image;

import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Resizes and compresses images before they are sent to the vision model, keeping
 * dimensions multiples of {@value #DIMENSION_MULTIPLE} and the payload size low.
 */
@Component
public class VisionImagePreprocessor {

    static final int DIMENSION_MULTIPLE = 32;
    static final double FLOATING_POINT_TOLERANCE = 1e-9;

    private final int maxEdge;
    private final float jpegQuality;

    public VisionImagePreprocessor(
            @Value("${zia.translation.vision.preprocessing.max-edge:1024}") int maxEdge,
            @Value("${zia.translation.vision.preprocessing.jpeg-quality:0.75}") float jpegQuality) {
        if (maxEdge < DIMENSION_MULTIPLE) {
            throw new IllegalArgumentException("zia.translation.vision.preprocessing.max-edge must be >= " + DIMENSION_MULTIPLE);
        }
        if (!Float.isFinite(jpegQuality) || jpegQuality <= 0.0f || jpegQuality > 1.0f) {
            throw new IllegalArgumentException("zia.translation.vision.preprocessing.jpeg-quality must be in range ]0.0, 1.0]");
        }
        this.maxEdge = maxEdge;
        this.jpegQuality = jpegQuality;
    }

    public record PreprocessedImage(byte[] bytes, MimeType mimeType) {
    }

    public PreprocessedImage preprocess(byte[] imageBytes) {
        BufferedImage source = readImage(imageBytes);

        int width = source.getWidth();
        int height = source.getHeight();

        double scale = Math.min(1.0, Math.min((double) maxEdge / width, (double) maxEdge / height));
        int targetWidth = roundDownToMultiple(width * scale);
        int targetHeight = roundDownToMultiple(height * scale);

        BufferedImage resized = resizeToRgb(source, targetWidth, targetHeight);
        return new PreprocessedImage(encodeJpeg(resized, jpegQuality), MimeTypeUtils.IMAGE_JPEG);
    }

    public Media toMedia(byte[] imageBytes) {
        PreprocessedImage preprocessed = preprocess(imageBytes);
        return new Media(preprocessed.mimeType(), new ByteArrayResource(preprocessed.bytes()));
    }

    private static BufferedImage readImage(byte[] imageBytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                throw new ImagePreprocessingException("Unable to read image: unsupported or corrupt format");
            }
            return image;
        } catch (IOException e) {
            throw new ImagePreprocessingException("Unable to read image", e);
        }
    }

    static int roundDownToMultiple(double value) {
        int floored = (int) Math.floor(value + FLOATING_POINT_TOLERANCE);
        int rounded = (floored / DIMENSION_MULTIPLE) * DIMENSION_MULTIPLE;
        return Math.max(DIMENSION_MULTIPLE, rounded);
    }

    private static BufferedImage resizeToRgb(BufferedImage source, int targetWidth, int targetHeight) {
        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return resized;
    }

    private static byte[] encodeJpeg(BufferedImage image, float quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new ImagePreprocessingException("No JPEG ImageWriter available");
        }
        ImageWriter writer = writers.next();
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try (ImageOutputStream imageOutputStream = ImageIO.createImageOutputStream(outputStream)) {
                writer.setOutput(imageOutputStream);
                ImageWriteParam writeParam = writer.getDefaultWriteParam();
                writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                writeParam.setCompressionQuality(quality);
                writer.write(null, new IIOImage(image, null, null), writeParam);
            }
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new ImagePreprocessingException("Unable to encode image as JPEG", e);
        } finally {
            writer.dispose();
        }
    }
}
