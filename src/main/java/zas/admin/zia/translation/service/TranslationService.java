package zas.admin.zia.translation.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import zas.admin.zia.translation.service.llm.TextTranslationService;
import zas.admin.zia.translation.service.ocr.OcrExtractionService;
import zas.admin.zia.translation.service.parser.DocumentParser;
import zas.admin.zia.translation.service.pdf.PdfGenerationService;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TranslationService {

    static final String STRATEGY_SINGLE = "single";
    static final String STRATEGY_DUAL = "dual";

    private static final String PDF_MIME_TYPE = "application/pdf";
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F'};

    private final Map<String, DocumentParser> parsersByMimeType;
    private final OcrExtractionService ocrService;
    private final TextTranslationService textTranslationService;
    private final PdfGenerationService pdfGenerationService;
    private final String strategy;
    private final long maxFileSizeBytes;

    TranslationService(
            List<DocumentParser> parsers,
            OcrExtractionService ocrService,
            TextTranslationService textTranslationService,
            PdfGenerationService pdfGenerationService,
            @Value("${zia.translation.strategy}") String strategy,
            @Value("${zia.translation.pdf.max-file-size}") String maxFileSize) {
        this.parsersByMimeType = parsers.stream()
                .collect(Collectors.toMap(DocumentParser::supportedMimeType, Function.identity()));
        this.ocrService = ocrService;
        this.textTranslationService = textTranslationService;
        this.pdfGenerationService = pdfGenerationService;
        this.strategy = strategy;
        this.maxFileSizeBytes = parseSize(maxFileSize);
    }

    public List<String> translateToText(MultipartFile file, String targetLanguage) throws IOException {
        byte[] bytes = validateAndRead(file);
        DocumentParser parser = resolveParser(file, bytes);
        List<byte[]> pages;
        try {
            pages = parser.renderPages(bytes);
        } catch (IOException exception) {
            throw new InvalidDocumentException("Document is invalid or cannot be parsed.", exception);
        }
        return translatePages(pages, targetLanguage);
    }

    public byte[] translateToPdf(MultipartFile file, String targetLanguage) throws IOException {
        List<String> translatedPages = translateToText(file, targetLanguage);
        return pdfGenerationService.generatePdf(translatedPages);
    }

    private List<String> translatePages(List<byte[]> pages, String targetLanguage) {
        try {
            if (STRATEGY_SINGLE.equals(strategy)) {
                return textTranslationService.translatePagesSingleStrategy(pages, targetLanguage);
            }
            List<String> extracted = ocrService.extractText(pages);
            return textTranslationService.translatePages(extracted, targetLanguage);
        } catch (TranslationProcessingException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new TranslationProcessingException("Failed to process document translation.", ex);
        }
    }

    private byte[] validateAndRead(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new InvalidDocumentException("File is missing or empty.");
        }
        byte[] bytes = file.getBytes();
        if (bytes.length > maxFileSizeBytes) {
            throw new InvalidDocumentException(
                    "File size (%d bytes) exceeds maximum allowed (%d bytes).".formatted(bytes.length, maxFileSizeBytes));
        }
        return bytes;
    }

    private DocumentParser resolveParser(MultipartFile file, byte[] bytes) {
        String contentType = file.getContentType();
        DocumentParser parser = null;
        if (contentType != null) {
            parser = parsersByMimeType.get(contentType);
        }
        if (parser == null) {
            // fallback: check magic bytes
            if (isPdf(bytes)) {
                parser = parsersByMimeType.get(PDF_MIME_TYPE);
            }
        }
        if (parser == null) {
            throw new InvalidDocumentException(
                    "Unsupported file format: '%s'. Only PDF is currently supported.".formatted(contentType));
        }
        return parser;
    }

    private static boolean isPdf(byte[] bytes) {
        if (bytes.length < PDF_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (bytes[i] != PDF_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static long parseSize(String sizeStr) {
        if (sizeStr == null || sizeStr.isBlank()) {
            return 10L * 1024 * 1024;
        }
        String trimmed = sizeStr.trim().toUpperCase();
        if (trimmed.endsWith("MB")) {
            return Long.parseLong(trimmed.replace("MB", "").trim()) * 1024L * 1024;
        }
        if (trimmed.endsWith("KB")) {
            return Long.parseLong(trimmed.replace("KB", "").trim()) * 1024L;
        }
        return Long.parseLong(trimmed);
    }
}
