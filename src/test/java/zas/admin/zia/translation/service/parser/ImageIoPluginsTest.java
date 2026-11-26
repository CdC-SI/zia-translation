package zas.admin.zia.translation.service.parser;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImageIoPluginsTest {

    @Test
    void removeJaiImageIoCorePlugins_keepsJpeg2000AndTheUsualDecoders() {
        ImageIoPlugins.removeJaiImageIoCorePlugins();
        ImageIoPlugins.removeJaiImageIoCorePlugins();

        assertThat(registeredProviders()).filteredOn(name -> name.startsWith("com.github.jaiimageio."))
                .isNotEmpty()
                .allMatch(name -> name.startsWith("com.github.jaiimageio.jpeg2000."));
        assertThat(firstReader("JPEG2000")).startsWith("com.github.jaiimageio.jpeg2000.");
        assertThat(firstReader("tiff")).startsWith("com.twelvemonkeys.");
        assertThat(firstReader("bmp")).startsWith("com.sun.imageio.");
    }

    private static String firstReader(String format) {
        return ImageIO.getImageReadersByFormatName(format).next().getOriginatingProvider().getClass().getName();
    }

    private static List<String> registeredProviders() {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        List<String> names = new ArrayList<>();
        for (Iterator<Class<?>> categories = registry.getCategories(); categories.hasNext(); ) {
            registry.getServiceProviders(categories.next(), false)
                    .forEachRemaining(provider -> names.add(provider.getClass().getName()));
        }
        return names;
    }
}
