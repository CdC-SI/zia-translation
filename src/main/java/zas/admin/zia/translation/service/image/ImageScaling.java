package zas.admin.zia.translation.service.image;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;

/** Resizing of page images before they are sent to the vision model. */
public final class ImageScaling {

    private ImageScaling() {
    }

    /**
     * Resizes the image to {@code width} x {@code height} by area averaging: each output pixel is the mean of the
     * source pixels it covers, so a thin stroke is never skipped. Java2D's bicubic interpolation, by contrast, reads
     * only the 4 x 4 source pixels around each output pixel whatever the ratio: shrinking a phone photo by 3.5 it
     * ignores most pixels, and 1 px strokes vanish. The result is RGB on white; the image itself is returned when it
     * already has that size.
     */
    public static BufferedImage downscale(BufferedImage image, int width, int height) {
        if (image.getWidth() == width && image.getHeight() == height) {
            return image;
        }
        Image scaled = image.getScaledInstance(width, height, Image.SCALE_AREA_AVERAGING);
        BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(scaled, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rgb;
    }
}
