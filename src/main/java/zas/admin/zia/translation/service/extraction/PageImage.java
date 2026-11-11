package zas.admin.zia.translation.service.extraction;

/**
 * Rendered image of a page of the submitted file.
 *
 * @param page  1-based page of the file
 * @param image image bytes (PNG, JPEG...)
 */
record PageImage(int page, byte[] image) {
}
