package zas.admin.zia.translation.service.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import zas.admin.zia.translation.service.InvalidDocumentException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentParserResolverTest {

    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WEBP = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");

    private DocumentParser pdfParser;
    private DocumentParser imageParser;
    private DocumentParserResolver resolver;

    @BeforeEach
    void setUp() {
        pdfParser = mock(DocumentParser.class);
        when(pdfParser.supportedMimeTypes()).thenReturn(List.of("application/pdf"));
        imageParser = mock(DocumentParser.class);
        when(imageParser.supportedMimeTypes()).thenReturn(
                List.of("image/png", "image/jpeg", "image/gif", "image/bmp", "image/tiff", "image/webp"));
        resolver = new DocumentParserResolver(List.of(pdfParser, imageParser));
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpeg", "gif", "bmp", "tiff"})
    void resolve_imageBytes_chooseTheImageParser(String format) throws IOException {
        byte[] image = image(format);

        assertThat(DocumentParserResolver.detectMimeType(image)).isEqualTo("image/" + format);
        assertThat(resolver.resolve(image)).isSameAs(imageParser);
    }

    @Test
    void resolve_webp_choosesTheImageParser() {
        assertThat(resolver.resolve(WEBP)).isSameAs(imageParser);
    }

    @Test
    void resolve_pdf_choosesThePdfParserAndValidatesTheDocument() {
        assertThat(resolver.resolve(PDF)).isSameAs(pdfParser);
        verify(pdfParser).validate(PDF);
    }

    @Test
    void resolve_pdfHeaderAfterJunkBytes_stillPdf() {
        // readers accept %PDF- anywhere in the first 1024 bytes; Tika alone would say application/octet-stream
        byte[] prefixed = new byte[1000 + PDF.length];
        System.arraycopy(PDF, 0, prefixed, 1000, PDF.length);

        assertThat(resolver.resolve(prefixed)).isSameAs(pdfParser);
        assertThat(resolver.resolve("\n\n%PDF-1.4".getBytes(StandardCharsets.US_ASCII))).isSameAs(pdfParser);
    }

    @ParameterizedTest
    @ValueSource(strings = {"heic", "heix", "mif1", "avif"})
    void resolve_heicHeifAvif_rejectedWithExportAdvice(String brand) {
        assertThatThrownBy(() -> resolver.resolve(isoMediaHeader(brand)))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("HEIC/HEIF/AVIF images are not supported")
                .hasMessageContaining("export the photo as JPEG or PDF");
    }

    @Test
    void resolve_otherKnownFormat_rejectedWithDetectedType() {
        byte[] zip = new byte[64];
        System.arraycopy("PK\3\4".getBytes(StandardCharsets.ISO_8859_1), 0, zip, 0, 4);

        assertThatThrownBy(() -> resolver.resolve(zip))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("Unsupported document format 'application/zip'")
                .hasMessageContaining("Supported formats: application/pdf, image/png");
    }

    @Test
    void resolve_unknownOrEmptyBytes_rejected() {
        assertThatThrownBy(() -> resolver.resolve(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("Unrecognized document format");
        assertThatThrownBy(() -> resolver.resolve(new byte[0]))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("Unrecognized document format");
    }

    @Test
    void resolve_parserRejectsTheDocument_propagated() {
        doThrow(new InvalidDocumentException("too large")).when(pdfParser).validate(PDF);

        assertThatThrownBy(() -> resolver.resolve(PDF)).hasMessage("too large");
    }

    @Test
    void constructor_duplicateMimeType_rejected() {
        DocumentParser other = mock(DocumentParser.class);
        when(other.supportedMimeTypes()).thenReturn(List.of("application/pdf"));

        assertThatThrownBy(() -> new DocumentParserResolver(List.of(pdfParser, other)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static byte[] image(String format) throws IOException {
        try (var out = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), format, out);
            return out.toByteArray();
        }
    }

    /** Start of an ISO base media file (HEIC/HEIF/AVIF): box size, "ftyp", major brand, version, compatible brands. */
    private static byte[] isoMediaHeader(String brand) {
        byte[] header = new byte[64];
        byte[] ftyp = ("\0\0\0\u0018ftyp" + brand + "\0\0\0\0mif1" + brand).getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(ftyp, 0, header, 0, ftyp.length);
        return header;
    }
}
