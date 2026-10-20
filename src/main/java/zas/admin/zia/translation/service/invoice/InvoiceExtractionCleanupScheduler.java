package zas.admin.zia.translation.service.invoice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
class InvoiceExtractionCleanupScheduler {

    private final InvoiceExtractionJobStore jobStore;
    private final Duration jobTtl;

    InvoiceExtractionCleanupScheduler(
            InvoiceExtractionJobStore jobStore,
            @Value("${zia.invoice-extraction.job-ttl:24h}") Duration jobTtl) {
        this.jobStore = jobStore;
        this.jobTtl = jobTtl;
    }

    @Scheduled(fixedDelayString = "${zia.invoice-extraction.cleanup-interval:10m}")
    void cleanupExpiredJobs() {
        jobStore.findExpired(jobTtl, Instant.now()).forEach(job -> jobStore.delete(job.jobId()));
    }
}
