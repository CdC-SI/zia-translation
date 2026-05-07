package zas.admin.zia.translation.service.job;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zas.admin.zia.translation.service.storage.PdfStorageService;

import java.time.Duration;
import java.time.Instant;

@Component
class JobCleanupScheduler {

    private final TranslationJobStore translationJobStore;
    private final PdfStorageService pdfStorageService;
    private final Duration jobTtl;

    JobCleanupScheduler(
            TranslationJobStore translationJobStore,
            PdfStorageService pdfStorageService,
            @Value("${zia.translation.pdf.job-ttl}") Duration jobTtl) {
        this.translationJobStore = translationJobStore;
        this.pdfStorageService = pdfStorageService;
        this.jobTtl = jobTtl;
    }

    @Scheduled(fixedDelay = 600000)
    void cleanupExpiredJobs() {
        translationJobStore.findExpired(jobTtl, Instant.now()).forEach(job -> {
            pdfStorageService.delete(job.jobId());
            translationJobStore.delete(job.jobId());
        });
    }
}
