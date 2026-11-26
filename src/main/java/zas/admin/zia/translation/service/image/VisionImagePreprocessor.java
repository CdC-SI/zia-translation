package zas.admin.zia.translation.service.image;

import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Prepares page images for the vision model: content within the {@link PixelBudget} (pages normally come rendered
 * at it, see {@code DocumentPages.render}), padded with white to multiples of {@value PixelBudget#MULTIPLE} on the
 * right and bottom, encoded as PNG (lossless: no JPEG ringing around the strokes of small text).
 */
@Component
public class VisionImagePreprocessor {

    private final PixelBudget budget;

    /** @param maxPixels pixels of a sent image, padding included (at most the vision server's max_pixels) */
    public VisionImagePreprocessor(
            @Value("${zia.translation.vision.preprocessing.max-pixels:1000000}") long maxPixels) {
        this.budget = new PixelBudget(maxPixels);
    }

    /** The budget to render the pages at, before {@link #toMedia}. */
    public PixelBudget budget() {
        return budget;
    }

    public Media toMedia(BufferedImage image) {
        return new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(toPng(image)));
    }

    /** The PNG bytes {@link #toMedia} sends. */
    public byte[] toPng(BufferedImage image) {
        return encodePng(prepare(image));
    }

    /**
     * Shrinks the image if it does not fit the budget (a page rendered at the budget fits: it is only padded), then
     * pads it with white to multiples of {@value PixelBudget#MULTIPLE}.
     */
    BufferedImage prepare(BufferedImage image) {
        PixelBudget.Size size = budget.fitImage(image.getWidth(), image.getHeight());
        BufferedImage content = ImageScaling.downscale(image, size.width(), size.height());
        return pad(content, size.paddedWidth(), size.paddedHeight());
    }

    /** Draws the image at the top left of a white RGB canvas (which also flattens transparency on white). */
    private static BufferedImage pad(BufferedImage image, int width, int height) {
        if (image.getWidth() == width && image.getHeight() == height && image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage padded = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = padded.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return padded;
    }

    private static byte[] encodePng(BufferedImage image) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", outputStream)) {
                throw new ImagePreprocessingException("No PNG ImageWriter available");
            }
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new ImagePreprocessingException("Unable to encode image as PNG", e);
        }
    }
}
