package zas.admin.zia.translation.service.extraction;

import java.time.Instant;

record ExtractionJob(
        String jobId,
        String idempotencyKey,
        ExtractionStatus status,
        Instant submittedAt,
        Instant completedAt,
        ExtractionResponse result,
        ExtractionError error) {

    ExtractionJob running() {
        return new ExtractionJob(jobId, idempotencyKey, ExtractionStatus.RUNNING, submittedAt, null, null, null);
    }

    ExtractionJob succeeded(ExtractionResponse response, Instant now) {
        return new ExtractionJob(jobId, idempotencyKey, ExtractionStatus.SUCCEEDED, submittedAt, now, response, null);
    }

    ExtractionJob failed(ExtractionError extractionError, Instant now) {
        return new ExtractionJob(jobId, idempotencyKey, ExtractionStatus.FAILED, submittedAt, now, null, extractionError);
    }
}
