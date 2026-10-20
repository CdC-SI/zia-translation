package zas.admin.zia.translation.service.invoice;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceExtractionJobStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-20T14:38:03Z");

    private final InvoiceExtractionJobStore store = new InvoiceExtractionJobStore(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void submit_newKey_createsPendingJob() {
        InvoiceExtractionJobStore.Submission submission = store.submit("D_0035-3f2a");

        assertThat(submission.created()).isTrue();
        assertThat(submission.job().status()).isEqualTo(ExtractionStatus.PENDING);
        assertThat(submission.job().submittedAt()).isEqualTo(NOW);
        assertThat(store.findById(submission.job().jobId())).contains(submission.job());
    }

    @Test
    void submit_knownKey_returnsExistingJob() {
        String jobId = store.submit("key").job().jobId();
        store.markRunning(jobId);

        InvoiceExtractionJobStore.Submission again = store.submit("key");

        assertThat(again.created()).isFalse();
        assertThat(again.job().jobId()).isEqualTo(jobId);
        assertThat(again.job().status()).isEqualTo(ExtractionStatus.RUNNING);
    }

    @Test
    void submit_sameKeyConcurrently_createsSingleJob() throws InterruptedException {
        int threads = 16;
        Set<String> jobIds = ConcurrentHashMap.newKeySet();
        Set<Boolean> created = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    InvoiceExtractionJobStore.Submission submission = store.submit("same-key");
                    jobIds.add(submission.job().jobId());
                    if (submission.created()) {
                        created.add(Boolean.TRUE);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(jobIds).hasSize(1);
        assertThat(created).containsExactly(Boolean.TRUE);
    }

    @Test
    void markSucceeded_setsResultAndCompletion() {
        String jobId = store.submit("key").job().jobId();
        ExtractionResponse response = new ExtractionResponse("invoice", Map.of(), List.of(), List.of());

        store.markSucceeded(jobId, response);

        InvoiceExtractionJob job = store.findById(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(ExtractionStatus.SUCCEEDED);
        assertThat(job.result()).isEqualTo(response);
        assertThat(job.completedAt()).isEqualTo(NOW);
        assertThat(job.error()).isNull();
    }

    @Test
    void markFailed_setsError() {
        String jobId = store.submit("key").job().jobId();
        ExtractionError error = new ExtractionError(ExtractionError.UNREADABLE_DOCUMENT, "unreadable");

        store.markFailed(jobId, error);

        InvoiceExtractionJob job = store.findById(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(ExtractionStatus.FAILED);
        assertThat(job.error()).isEqualTo(error);
        assertThat(job.result()).isNull();
    }

    @Test
    void findExpired_returnsOnlyFinishedJobsOlderThanTtl() {
        String finished = store.submit("finished").job().jobId();
        store.markSucceeded(finished, new ExtractionResponse(null, Map.of(), List.of(), List.of()));
        store.submit("pending");

        assertThat(store.findExpired(Duration.ofHours(24), NOW.plus(Duration.ofHours(23)))).isEmpty();
        assertThat(store.findExpired(Duration.ofHours(24), NOW.plus(Duration.ofHours(25))))
                .extracting(InvoiceExtractionJob::jobId)
                .containsExactly(finished);
    }

    @Test
    void delete_forgetsJobAndKey() {
        String jobId = store.submit("key").job().jobId();

        store.delete(jobId);

        assertThat(store.findById(jobId)).isEmpty();
        InvoiceExtractionJobStore.Submission again = store.submit("key");
        assertThat(again.created()).isTrue();
        assertThat(again.job().jobId()).isNotEqualTo(jobId);
    }

    @Test
    void cleanupScheduler_deletesExpiredJobs() {
        InvoiceExtractionJobStore realClockStore = new InvoiceExtractionJobStore();
        String jobId = realClockStore.submit("key").job().jobId();
        realClockStore.markFailed(jobId, new ExtractionError(ExtractionError.INTERNAL_ERROR, "boom"));

        new InvoiceExtractionCleanupScheduler(realClockStore, Duration.ofMillis(-1)).cleanupExpiredJobs();

        assertThat(realClockStore.findById(jobId)).isEmpty();
    }
}
