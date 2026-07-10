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
        assertThat(job.outputFormat()).isEqualTo(JobOutputFormat.PDF);
        assertThat(store.findById(job.jobId())).isPresent();
    }

    @Test
    void createPendingJob_withMarkdownFormat_createsMarkdownJob() {
        TranslationJob job = store.createPendingJob(JobOutputFormat.MARKDOWN);

        assertThat(job.outputFormat()).isEqualTo(JobOutputFormat.MARKDOWN);
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
    void markProcessingAndCompleted_preservesOutputFormat() {
        TranslationJob job = store.createPendingJob(JobOutputFormat.MARKDOWN);

        store.markProcessing(job.jobId());
        assertThat(store.findById(job.jobId()).orElseThrow().outputFormat()).isEqualTo(JobOutputFormat.MARKDOWN);

        store.markCompleted(job.jobId());
        assertThat(store.findById(job.jobId()).orElseThrow().outputFormat()).isEqualTo(JobOutputFormat.MARKDOWN);
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
    void markFailed_preservesOutputFormat() {
        TranslationJob job = store.createPendingJob(JobOutputFormat.MARKDOWN);

        store.markFailed(job.jobId(), "Translation failed");

        assertThat(store.findById(job.jobId()).orElseThrow().outputFormat()).isEqualTo(JobOutputFormat.MARKDOWN);
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
