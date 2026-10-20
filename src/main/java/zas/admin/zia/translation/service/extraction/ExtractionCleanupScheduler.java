package zas.admin.zia.translation.service.extraction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
class ExtractionCleanupScheduler {

    private final ExtractionJobStore jobStore;
    private final Duration jobTtl;

    ExtractionCleanupScheduler(
            ExtractionJobStore jobStore,
            @Value("${zia.extraction.job-ttl:24h}") Duration jobTtl) {
        this.jobStore = jobStore;
        this.jobTtl = jobTtl;
    }

    @Scheduled(fixedDelayString = "${zia.extraction.cleanup-interval:10m}")
    void cleanupExpiredJobs() {
        jobStore.findExpired(jobTtl, Instant.now()).forEach(job -> jobStore.delete(job.jobId()));
    }
}
