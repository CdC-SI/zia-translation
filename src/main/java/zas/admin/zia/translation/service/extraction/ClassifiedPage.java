package zas.admin.zia.translation.service.extraction;

/**
 * Classification of a page by the vision model, with the hints of continuity used to consolidate the pages of a
 * document.
 *
 * @param printedPageNumber     page number printed on the page (e.g. 2 for "Seite 2 von 2"), {@code null} if none
 * @param printedPageCount      page count printed on the page, {@code null} if none
 * @param continuesPreviousPage the page continues the previous page of the file according to the model
 */
record ClassifiedPage(
        PageClassification classification,
        Integer printedPageNumber,
        Integer printedPageCount,
        boolean continuesPreviousPage) {
}
