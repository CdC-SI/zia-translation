package zas.admin.zia.translation.service.parser;

import zas.admin.zia.translation.service.image.PixelBudget;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory {@link DocumentPages} for service tests: one distinct 1x1 image per page, A4 layouts, and a record of
 * the rendered pages and of the close call.
 */
public final class TestDocumentPages implements DocumentPages {

    private static final PageLayout A4 = new PageLayout(595f, 842f);

    private final List<BufferedImage> images = new ArrayList<>();
    private final List<PageLayout> layouts = new ArrayList<>();
    private final List<Integer> rendered = Collections.synchronizedList(new ArrayList<>());
    private final List<PixelBudget> budgets = Collections.synchronizedList(new ArrayList<>());
    private IOException renderFailure;
    private volatile boolean closed;

    private TestDocumentPages(List<PageLayout> layouts) {
        for (int i = 0; i < layouts.size(); i++) {
            BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, i + 1);
            images.add(image);
        }
        this.layouts.addAll(layouts);
    }

    public static TestDocumentPages of(int pageCount) {
        return new TestDocumentPages(Collections.nCopies(pageCount, A4));
    }

    public static TestDocumentPages withLayouts(PageLayout... layouts) {
        return new TestDocumentPages(List.of(layouts));
    }

    /** Every {@link #render} call fails with this exception. */
    public TestDocumentPages failingRender(IOException failure) {
        this.renderFailure = failure;
        return this;
    }

    /** The image returned for the page (index from 0). */
    public BufferedImage image(int index) {
        return images.get(index);
    }

    /** Indexes (from 0) of the rendered pages, in call order. */
    public List<Integer> renderedPages() {
        return List.copyOf(rendered);
    }

    /** Budgets of the {@link #render} calls, in call order. */
    public List<PixelBudget> renderBudgets() {
        return List.copyOf(budgets);
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public int pageCount() {
        return images.size();
    }

    @Override
    public PageLayout layout(int index) {
        return layouts.get(index);
    }

    @Override
    public BufferedImage render(int index, PixelBudget budget) throws IOException {
        rendered.add(index);
        budgets.add(budget);
        if (renderFailure != null) {
            throw renderFailure;
        }
        return images.get(index);
    }

    @Override
    public void close() {
        closed = true;
    }
}
