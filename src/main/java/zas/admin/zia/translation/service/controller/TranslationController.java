package zas.admin.zia.translation.service.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import zas.admin.zia.translation.service.TranslationService;
import zas.admin.zia.translation.service.dto.TranslationTextResponse;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/translation")
class TranslationController {

    private final TranslationService translationService;

    TranslationController(TranslationService translationService) {
        this.translationService = translationService;
    }

    @PostMapping("/pdf")
    ResponseEntity<byte[]> translateToPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam("targetLanguage") String targetLanguage) throws IOException {

        byte[] pdfBytes = translationService.translateToPdf(file, targetLanguage);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"translated.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }

    @PostMapping("/text")
    ResponseEntity<TranslationTextResponse> translateToText(
            @RequestParam("file") MultipartFile file,
            @RequestParam("targetLanguage") String targetLanguage) throws IOException {

        List<String> pages = translationService.translateToText(file, targetLanguage);
        return ResponseEntity.ok(new TranslationTextResponse(pages));
    }
}
