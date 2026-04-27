package zas.admin.zia.translation.service.parser;

/**
 * Holds the dimensions of a document page in PDF points (1 pt = 1/72 inch).
 *
 * @param widthPt  page width in points
 * @param heightPt page height in points
 */
public record PageLayout(float widthPt, float heightPt) {

    boolean isLandscape() {
        return widthPt > heightPt;
    }
}

