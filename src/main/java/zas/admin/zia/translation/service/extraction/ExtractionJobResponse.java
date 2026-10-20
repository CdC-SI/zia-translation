package zas.admin.zia.translation.service.extraction;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * {@code ExtractionJob} of the contract: {@code result} is set when {@code SUCCEEDED}, {@code error} when {@code FAILED}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record ExtractionJobResponse(
        String jobId,
        ExtractionStatus status,
        Instant submittedAt,
        ExtractionResponse result,
        ExtractionError error) {

    static ExtractionJobResponse of(ExtractionJob job) {
        return new ExtractionJobResponse(job.jobId(), job.status(), job.submittedAt(), job.result(), job.error());
    }
}
