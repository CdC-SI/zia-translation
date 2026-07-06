package zas.admin.zia.translation.service.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import zas.admin.zia.translation.service.InvalidDocumentException;
import zas.admin.zia.translation.service.TranslationProcessingException;
import zas.admin.zia.translation.service.TranslationService;
import zas.admin.zia.translation.service.dto.TranslationCompleteEvent;
import zas.admin.zia.translation.service.dto.TranslationJobResponse;
import zas.admin.zia.translation.service.dto.TranslationPageEvent;
import zas.admin.zia.translation.service.dto.TranslationStreamEvent;
import zas.admin.zia.translation.service.job.JobStatus;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/translation")
class TranslationController {

    private static final Logger log = LoggerFactory.getLogger(TranslationController.class);

    private final TranslationService translationService;
    private final ObjectMapper objectMapper;

    TranslationController(TranslationService translationService, ObjectMapper objectMapper) {
        this.translationService = translationService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/pdf")
    ResponseEntity<TranslationJobResponse> translateToPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam("targetLanguage") String targetLanguage) throws IOException {

        log.info("Received async request to translate file '{}' to language '{}'", file.getOriginalFilename(), targetLanguage);

        TranslationJobResponse response = translationService.submitPdfTranslation(file, targetLanguage);

        return ResponseEntity.accepted().body(response);
    }

    @PostMapping("/md")
    ResponseEntity<TranslationJobResponse> translateToMarkdown(
            @RequestParam("file") MultipartFile file,
            @RequestParam("targetLanguage") String targetLanguage) throws IOException {

        log.info("Received async request to translate file '{}' to Markdown in language '{}'", file.getOriginalFilename(), targetLanguage);

        TranslationJobResponse response = translationService.submitMarkdownTranslation(file, targetLanguage);

        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/jobs/{jobId}/status")
    ResponseEntity<TranslationJobResponse> getJobStatus(@PathVariable String jobId) {
        return translationService.getJobStatusResponse(jobId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/jobs/{jobId}")
    ResponseEntity<Resource> downloadJob(@PathVariable String jobId) {
        JobStatus status = translationService.getJobStatus(jobId).orElse(null);

        if (status == null) {
            return ResponseEntity.notFound().build();
        }

        if (status == JobStatus.PENDING || status == JobStatus.PROCESSING) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        if (status == JobStatus.FAILED) {
            return ResponseEntity.unprocessableEntity().build();
        }

        return translationService.getTranslatedFile(jobId)
                .map(file -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                        .contentType(file.mediaType())
                        .body(file.resource()))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.GONE).build());
    }

    /**
     * @deprecated use {@link #getJobStatus(String)} via {@code GET /api/translation/jobs/{jobId}/status}.
     */
    @Deprecated
    @GetMapping("/pdf/{jobId}/status")
    ResponseEntity<TranslationJobResponse> getPdfJobStatus(@PathVariable String jobId) {
        return getJobStatus(jobId);
    }

    /**
     * @deprecated use {@link #downloadJob(String)} via {@code GET /api/translation/jobs/{jobId}}.
     */
    @Deprecated
    @GetMapping("/pdf/{jobId}")
    ResponseEntity<Resource> downloadPdf(@PathVariable String jobId) {
        return downloadJob(jobId);
    }

    @PostMapping(value = "/text", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<String>> translateToText(
            @RequestParam("file") MultipartFile file,
            @RequestParam("targetLanguage") String targetLanguage) throws IOException {

        log.info("Received request to stream translated text for file '{}' to language '{}'", file.getOriginalFilename(), targetLanguage);

        AtomicInteger totalPages = new AtomicInteger(0);

        Flux<ServerSentEvent<String>> streamEvents = translationService.translateToTextStream(file, targetLanguage)
                .map(event -> toSseEvent(totalPages, event));

        Mono<ServerSentEvent<String>> completeEvent = Mono.fromSupplier(() -> ServerSentEvent.<String>builder()
                .event("complete")
                .data(toJson(new TranslationCompleteEvent(totalPages.get())))
                .build());

        return streamEvents
                .concatWith(completeEvent)
                .onErrorResume(exception -> {
                    log.error("SSE translation stream failed", exception);
                    return Mono.just(ServerSentEvent.<String>builder()
                            .event("error")
                            .data(toJson(Map.of("message", resolveErrorMessage(exception))))
                            .build());
                });
    }

    private ServerSentEvent<String> toSseEvent(AtomicInteger totalPages, TranslationStreamEvent event) {
        return switch (event) {
            case TranslationStreamEvent.Token token -> ServerSentEvent.<String>builder()
                    .event("token")
                    .data(toJson(token))
                    .build();
            case TranslationStreamEvent.PageComplete page -> {
                totalPages.incrementAndGet();
                yield ServerSentEvent.<String>builder()
                        .event("page")
                        .data(toJson(new TranslationPageEvent(page.pageNumber(), page.text())))
                        .build();
            }
        };
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize SSE payload", exception);
        }
    }

    private String resolveErrorMessage(Throwable throwable) {
        if (throwable instanceof InvalidDocumentException || throwable instanceof TranslationProcessingException) {
            return "Translation processing failed.";
        }
        return "Unexpected error during streaming.";
    }
}
