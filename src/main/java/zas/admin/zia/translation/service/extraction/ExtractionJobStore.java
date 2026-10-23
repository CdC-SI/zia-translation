package zas.admin.zia.translation.service.extraction;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * In-memory store of the extraction jobs, idempotent on the {@code Idempotency-Key} of the submission.
 */
@Component
class ExtractionJobStore {

    private final ConcurrentHashMap<String, ExtractionJob> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> jobIdsByKey = new ConcurrentHashMap<>();
    private final Clock clock;

    ExtractionJobStore() {
        this(Clock.systemUTC());
    }

    ExtractionJobStore(Clock clock) {
        this.clock = clock;
    }

    /**
     * Returns the job of the given key, creating a {@code PENDING} one when the key is unknown.
     */
    Submission submit(String idempotencyKey) {
        return submit(idempotencyKey, job -> {
        });
    }

    /**
     * Returns the job of the given key, creating a {@code PENDING} one when the key is unknown and admitting it with
     * {@code admission} (e.g. scheduling its processing) before the key is published. Concurrent submissions of the
     * same key wait for the admission: they get the admitted job or, when the admission fails, try again to create
     * and admit their own job.
     *
     * @throws RuntimeException the exception of the admission; the job is then forgotten
     */
    Submission submit(String idempotencyKey, Consumer<ExtractionJob> admission) {
        while (true) {
            boolean[] created = {false};
            String jobId = jobIdsByKey.computeIfAbsent(idempotencyKey, key -> {
                String newJobId = UUID.randomUUID().toString();
                ExtractionJob newJob = new ExtractionJob(newJobId, key, ExtractionStatus.PENDING,
                        clock.instant(), null, null, null);
                jobs.put(newJobId, newJob);
                try {
                    admission.accept(newJob);
                } catch (RuntimeException ex) {
                    jobs.remove(newJobId);
                    throw ex;
                }
                created[0] = true;
                return newJobId;
            });
            ExtractionJob job = jobs.get(jobId);
            if (job != null) {
                return new Submission(job, created[0]);
            }
            // the job expired between the two lookups: drop the stale key and create a new job
            jobIdsByKey.remove(idempotencyKey, jobId);
        }
    }

    Optional<ExtractionJob> findById(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    void markRunning(String jobId) {
        update(jobId, ExtractionJob::running);
    }

    void markSucceeded(String jobId, ExtractionResponse response) {
        update(jobId, job -> job.succeeded(response, clock.instant()));
    }

    void markFailed(String jobId, ExtractionError error) {
        update(jobId, job -> job.failed(error, clock.instant()));
    }

    List<ExtractionJob> findExpired(Duration ttl, Instant now) {
        return jobs.values().stream()
                .filter(job -> job.completedAt() != null)
                .filter(job -> job.completedAt().plus(ttl).isBefore(now))
                .toList();
    }

    void delete(String jobId) {
        ExtractionJob removed = jobs.remove(jobId);
        if (removed != null) {
            jobIdsByKey.remove(removed.idempotencyKey(), jobId);
        }
    }

    private void update(String jobId, UnaryOperator<ExtractionJob> transition) {
        jobs.computeIfPresent(jobId, (ignored, existing) -> transition.apply(existing));
    }

    /**
     * @param created {@code true} when the job was created by this submission, {@code false} when it already existed
     */
    record Submission(ExtractionJob job, boolean created) {
    }
}
