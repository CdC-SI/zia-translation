package zas.admin.zia.translation.service.parser;

import javax.imageio.spi.IIORegistry;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * ImageIO plugins of the parsers. The JPEG 2000 decoder (jai-imageio-jpeg2000, used by PDFBox for JPXDecode images)
 * needs jai-imageio-core, which also registers its own TIFF, BMP, WBMP, PNM, PCX, raw and stream plugins ahead of
 * TwelveMonkeys and the JDK. Those are removed so that image uploads keep their usual decoders; only the JPEG 2000
 * plugins of jai-imageio stay. {@link javax.imageio.ImageIO#scanForPlugins()} would register them again.
 */
final class ImageIoPlugins {

    private static final String JAI_IMAGEIO = "com.github.jaiimageio.";
    private static final String JAI_IMAGEIO_JPEG2000 = "com.github.jaiimageio.jpeg2000.";

    private ImageIoPlugins() {
    }

    /** Removes the jai-imageio-core plugins from the default ImageIO registry (idempotent). */
    static synchronized void removeJaiImageIoCorePlugins() {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        List<Object> toRemove = new ArrayList<>();
        for (Iterator<Class<?>> categories = registry.getCategories(); categories.hasNext(); ) {
            for (Iterator<?> providers = registry.getServiceProviders(categories.next(), false); providers.hasNext(); ) {
                Object provider = providers.next();
                String name = provider.getClass().getName();
                if (name.startsWith(JAI_IMAGEIO) && !name.startsWith(JAI_IMAGEIO_JPEG2000)) {
                    toRemove.add(provider);
                }
            }
        }
        toRemove.forEach(registry::deregisterServiceProvider);
    }
}
