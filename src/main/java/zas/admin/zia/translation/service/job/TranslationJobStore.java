package zas.admin.zia.translation.service.job;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TranslationJobStore {

    private final ConcurrentHashMap<String, TranslationJob> jobs = new ConcurrentHashMap<>();

    public TranslationJob createPendingJob() {
        return createPendingJob(JobOutputFormat.PDF);
    }

    public TranslationJob createPendingJob(JobOutputFormat outputFormat) {
        String jobId = UUID.randomUUID().toString();
        TranslationJob job = new TranslationJob(jobId, JobStatus.PENDING, Instant.now(), null, null, outputFormat);
        jobs.put(jobId, job);
        return job;
    }

    public Optional<TranslationJob> findById(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public void markProcessing(String jobId) {
        jobs.computeIfPresent(jobId, (ignored, existing) -> new TranslationJob(
                existing.jobId(),
                JobStatus.PROCESSING,
                existing.createdAt(),
                null,
                null,
                existing.outputFormat()));
    }

    public void markCompleted(String jobId) {
        jobs.computeIfPresent(jobId, (ignored, existing) -> new TranslationJob(
                existing.jobId(),
                JobStatus.COMPLETED,
                existing.createdAt(),
                Instant.now(),
                null,
                existing.outputFormat()));
    }

    public void markFailed(String jobId, String errorMessage) {
        jobs.computeIfPresent(jobId, (ignored, existing) -> new TranslationJob(
                existing.jobId(),
                JobStatus.FAILED,
                existing.createdAt(),
                Instant.now(),
                errorMessage,
                existing.outputFormat()));
    }

    public List<TranslationJob> findExpired(Duration ttl, Instant now) {
        return jobs.values().stream()
                .filter(job -> job.completedAt() != null)
                .filter(job -> job.completedAt().plus(ttl).isBefore(now))
                .toList();
    }

    public void delete(String jobId) {
        jobs.remove(jobId);
    }
}
