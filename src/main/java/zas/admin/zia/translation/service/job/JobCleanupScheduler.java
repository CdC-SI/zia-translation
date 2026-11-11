package zas.admin.zia.translation.service.job;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zas.admin.zia.translation.service.storage.MarkdownStorageService;
import zas.admin.zia.translation.service.storage.PdfStorageService;

import java.time.Duration;
import java.time.Instant;

@Component
class JobCleanupScheduler {

    private final TranslationJobStore translationJobStore;
    private final PdfStorageService pdfStorageService;
    private final MarkdownStorageService markdownStorageService;
    private final Duration jobTtl;

    JobCleanupScheduler(
            TranslationJobStore translationJobStore,
            PdfStorageService pdfStorageService,
            MarkdownStorageService markdownStorageService,
            @Value("${zia.translation.pdf.job-ttl}") Duration jobTtl) {
        this.translationJobStore = translationJobStore;
        this.pdfStorageService = pdfStorageService;
        this.markdownStorageService = markdownStorageService;
        this.jobTtl = jobTtl;
    }

    @Scheduled(fixedDelayString = "${zia.translation.pdf.cleanup-interval:10m}")
    void cleanupExpiredJobs() {
        translationJobStore.findExpired(jobTtl, Instant.now()).forEach(job -> {
            if (job.outputFormat() == JobOutputFormat.MARKDOWN) {
                markdownStorageService.delete(job.jobId());
            } else {
                pdfStorageService.delete(job.jobId());
            }
            translationJobStore.delete(job.jobId());
        });
    }
}
