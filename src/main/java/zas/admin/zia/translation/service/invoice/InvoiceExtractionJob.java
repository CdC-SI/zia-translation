package zas.admin.zia.translation.service.invoice;

import java.time.Instant;

record InvoiceExtractionJob(
        String jobId,
        String idempotencyKey,
        ExtractionStatus status,
        Instant submittedAt,
        Instant completedAt,
        ExtractionResponse result,
        ExtractionError error) {

    InvoiceExtractionJob running() {
        return new InvoiceExtractionJob(jobId, idempotencyKey, ExtractionStatus.RUNNING, submittedAt, null, null, null);
    }

    InvoiceExtractionJob succeeded(ExtractionResponse response, Instant now) {
        return new InvoiceExtractionJob(jobId, idempotencyKey, ExtractionStatus.SUCCEEDED, submittedAt, now, response, null);
    }

    InvoiceExtractionJob failed(ExtractionError extractionError, Instant now) {
        return new InvoiceExtractionJob(jobId, idempotencyKey, ExtractionStatus.FAILED, submittedAt, now, null, extractionError);
    }
}
