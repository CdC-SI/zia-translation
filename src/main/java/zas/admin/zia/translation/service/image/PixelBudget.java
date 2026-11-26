package zas.admin.zia.translation.service.image;

/**
 * Number of pixels an image sent to the vision model may have, padding included. The model reads images in squares
 * of {@value #MULTIPLE} x {@value #MULTIPLE} pixels (one token each), so an image is sent with sides that are
 * multiples of 32: its content keeps the exact aspect ratio of the page and is padded with white up to the next
 * multiple, instead of being squashed to it.
 *
 * <p>Keep it at or below the {@code max_pixels} of the vision server, otherwise the server resizes the image again.
 *
 * @param maxPixels pixels of the padded image, at most
 */
public record PixelBudget(long maxPixels) {

    public static final int MULTIPLE = 32;
    private static final double TOLERANCE = 1e-9;
    private static final double STEP_MARGIN = 1e-6;

    public PixelBudget {
        if (maxPixels < (long) MULTIPLE * MULTIPLE) {
            throw new IllegalArgumentException("The pixel budget must be >= " + MULTIPLE * MULTIPLE + ": " + maxPixels);
        }
    }

    /**
     * Size of the content once padded to multiples of {@value #MULTIPLE}.
     *
     * @param width  content width in pixels
     * @param height content height in pixels
     * @param scale  pixels of content per unit of the source (pixel for an image, point for a PDF page)
     */
    public record Size(int width, int height, double scale) {

        public int paddedWidth() {
            return padded(width);
        }

        public int paddedHeight() {
            return padded(height);
        }
    }

    /** Content size of an image of {@code width} x {@code height} pixels: never enlarged. */
    public Size fitImage(int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Image size must be positive: " + width + "x" + height);
        }
        return fit(width, height, true);
    }

    /** Content size of a page of {@code widthPt} x {@code heightPt} points, rendered at the scale that fills the budget. */
    public Size fitPage(double widthPt, double heightPt) {
        if (!(widthPt > 0) || !(heightPt > 0)) {
            throw new IllegalArgumentException("Page size must be positive: " + widthPt + "x" + heightPt);
        }
        return fit(widthPt, heightPt, false);
    }

    /** Whether an image of this size, once padded to multiples of {@value #MULTIPLE}, is within the budget. */
    public boolean fits(int width, int height) {
        return (long) padded(width) * padded(height) <= maxPixels;
    }

    /**
     * Scale (capped at 1 when the source must not be enlarged) whose padded size is within the budget: starts from
     * the scale that fills the budget without padding, then, while padding overflows, lowers the scale just enough to
     * bring one side down to its previous multiple of 32 (the side that needs the smaller reduction). Each step
     * removes 32 px from a padded side, so it ends, at the latest, at 32 x 32.
     */
    private Size fit(double width, double height, boolean neverEnlarge) {
        double scale = Math.sqrt(maxPixels / (width * height));
        if (neverEnlarge) {
            scale = Math.min(1.0, scale);
        }
        while (true) {
            int contentWidth = side(width, scale);
            int contentHeight = side(height, scale);
            if (fits(contentWidth, contentHeight)) {
                return new Size(contentWidth, contentHeight, scale);
            }
            scale = Math.max(stepDown(width, contentWidth), stepDown(height, contentHeight));
        }
    }

    /**
     * Largest scale at which a side of {@code length} gives, at most, the previous multiple of 32 of its current
     * content side: just below the scale where it would reach that multiple + 1. A side already within 32 px cannot
     * go lower (it stays at least 1 px): 0, so that the other side is reduced.
     */
    private static double stepDown(double length, int contentSide) {
        int previousMultiple = padded(contentSide) - MULTIPLE;
        return previousMultiple == 0 ? 0 : (previousMultiple + 1 - STEP_MARGIN) / length;
    }

    private static int side(double length, double scale) {
        return (int) Math.max(1, Math.floor(length * scale + TOLERANCE));
    }

    static int padded(int side) {
        return Math.ceilDiv(side, MULTIPLE) * MULTIPLE;
    }
}
