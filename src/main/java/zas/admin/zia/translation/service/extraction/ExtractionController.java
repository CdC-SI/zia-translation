package zas.admin.zia.translation.service.extraction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;
import zas.admin.zia.translation.service.config.TranslatorAccess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Asynchronous extraction of values from a document (PDF or image) with the vision model.
 */
@RestController
@RequestMapping(ExtractionController.BASE_PATH)
@PreAuthorize(TranslatorAccess.PRE_AUTHORIZE_EXPRESSION)
class ExtractionController {

    static final String BASE_PATH = "/api/v1/extractions";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final Logger log = LoggerFactory.getLogger(ExtractionController.class);

    private final ExtractionService extractionService;
    private final String retryAfterSeconds;

    ExtractionController(
            ExtractionService extractionService,
            @Value("${zia.extraction.retry-after:5}") int retryAfterSeconds) {
        this.extractionService = extractionService;
        this.retryAfterSeconds = String.valueOf(Math.max(0, retryAfterSeconds));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ExtractionJobResponse> submit(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @RequestPart("file") MultipartFile file,
            @RequestPart("context") ExtractionContext context) throws IOException {

        log.info("Received extraction request for file '{}'", file.getOriginalFilename());

        ExtractionJobResponse job = extractionService.submit(idempotencyKey, file, context);

        // relative to the submission URL, so that it stays valid behind a gateway rewriting the path prefix
        String location = "extractions/" + UriUtils.encodePathSegment(job.jobId(), StandardCharsets.UTF_8);
        return ResponseEntity.accepted()
                .header(HttpHeaders.LOCATION, location)
                .header(HttpHeaders.RETRY_AFTER, retryAfterSeconds)
                .body(job);
    }

    @GetMapping(path = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ExtractionJobResponse> get(@PathVariable String jobId) {
        return extractionService.find(jobId)
                .map(job -> {
                    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
                    if (!job.status().isFinished()) {
                        response.header(HttpHeaders.RETRY_AFTER, retryAfterSeconds);
                    }
                    return response.body(job);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
