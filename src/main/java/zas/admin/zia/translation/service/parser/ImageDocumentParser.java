package zas.admin.zia.translation.service.parser;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@Component
class ImageDocumentParser implements DocumentParser {

    private static final List<String> SUPPORTED_MIME_TYPES = List.of(
            "image/png",
            "image/jpeg",
            "image/jpg",
            "image/gif",
            "image/bmp",
            "image/webp",
            "image/tiff");

    @Override
    public List<byte[]> renderPages(byte[] documentBytes) throws IOException {
        return List.of(convertToPng(documentBytes));
    }

    @Override
    public List<String> supportedMimeTypes() {
        return SUPPORTED_MIME_TYPES;
    }

    @Override
    public List<PageLayout> extractPageLayouts(byte[] documentBytes) throws IOException {
        BufferedImage image = readImage(documentBytes);
        return List.of(new PageLayout(image.getWidth(), image.getHeight()));
    }

    private byte[] convertToPng(byte[] imageBytes) throws IOException {
        BufferedImage image = readImage(imageBytes);
        try (var outputStream = new ByteArrayOutputStream()) {
            boolean written = ImageIO.write(image, "png", outputStream);
            if (!written) {
                throw new IOException("No suitable ImageIO writer found for PNG format.");
            }
            return outputStream.toByteArray();
        }
    }

    private BufferedImage readImage(byte[] imageBytes) throws IOException {
        try (var inputStream = new ByteArrayInputStream(imageBytes)) {
            BufferedImage image = ImageIO.read(inputStream);
            if (image == null) {
                throw new IOException("Unable to read image: format not recognized or data is corrupted.");
            }
            return image;
        }
    }
}
