package zas.admin.zia.translation.service.parser;

import java.util.List;

/**
 * First pages of a document rendered as PNG images.
 *
 * @param pages     PNG image bytes of the rendered pages, from the first page
 * @param pageCount number of pages of the document, rendered or not
 */
public record RenderedPages(List<byte[]> pages, int pageCount) {

    public RenderedPages {
        pages = List.copyOf(pages);
    }
}
