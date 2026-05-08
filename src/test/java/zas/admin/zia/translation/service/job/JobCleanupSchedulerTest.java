package zas.admin.zia.translation.service.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zas.admin.zia.translation.service.storage.PdfStorageService;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class JobCleanupSchedulerTest {

    @Mock
    private PdfStorageService pdfStorageService;

    @Test
    void cleanupExpiredJobs_removesExpiredJobsAndFiles() {
        TranslationJobStore store = new TranslationJobStore();
        TranslationJob completed = store.createPendingJob();
        TranslationJob processing = store.createPendingJob();
        store.markCompleted(completed.jobId());
        store.markProcessing(processing.jobId());

        JobCleanupScheduler scheduler = new JobCleanupScheduler(store, pdfStorageService, Duration.ofSeconds(-1));

        scheduler.cleanupExpiredJobs();

        assertThat(store.findById(completed.jobId())).isEmpty();
        assertThat(store.findById(processing.jobId())).isPresent();
        verify(pdfStorageService).delete(completed.jobId());
        verifyNoMoreInteractions(pdfStorageService);
    }
}
