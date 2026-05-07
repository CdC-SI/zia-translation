package zas.admin.zia.translation.service.job;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TranslationJobStoreTest {

    private final TranslationJobStore store = new TranslationJobStore();

    @Test
    void createPendingJob_createsPendingStatusWithUuid() {
        TranslationJob job = store.createPendingJob();

        assertThat(job.jobId()).isNotBlank();
        assertThat(job.status()).isEqualTo(JobStatus.PENDING);
        assertThat(store.findById(job.jobId())).isPresent();
    }

    @Test
    void markProcessingAndCompleted_updatesStatusAndCompletionDate() {
        TranslationJob job = store.createPendingJob();

        store.markProcessing(job.jobId());
        store.markCompleted(job.jobId());

        TranslationJob updated = store.findById(job.jobId()).orElseThrow();
        assertThat(updated.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(updated.completedAt()).isNotNull();
    }

    @Test
    void markFailed_setsFailedStatusAndError() {
        TranslationJob job = store.createPendingJob();

        store.markFailed(job.jobId(), "OCR failed");

        TranslationJob updated = store.findById(job.jobId()).orElseThrow();
        assertThat(updated.status()).isEqualTo(JobStatus.FAILED);
        assertThat(updated.errorMessage()).isEqualTo("OCR failed");
        assertThat(updated.completedAt()).isNotNull();
    }

    @Test
    void findExpired_returnsOnlyCompletedJobsPastTtl() {
        TranslationJob first = store.createPendingJob();
        TranslationJob second = store.createPendingJob();
        store.markCompleted(first.jobId());
        store.markProcessing(second.jobId());

        List<TranslationJob> expired = store.findExpired(Duration.ofSeconds(-1), Instant.now());

        assertThat(expired).extracting(TranslationJob::jobId).containsExactly(first.jobId());
    }
}
