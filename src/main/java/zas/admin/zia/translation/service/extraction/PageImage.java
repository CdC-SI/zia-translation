package zas.admin.zia.translation.service.extraction;

import java.awt.image.BufferedImage;

/**
 * Rendered image of a page of the submitted file.
 *
 * @param page  1-based page of the file
 * @param image the rendered page (RGB), rendered on demand and dropped after use
 */
record PageImage(int page, BufferedImage image) {
}
