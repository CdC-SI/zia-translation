package zas.admin.zia.translation.service.job;

import java.time.Instant;

public record TranslationJob(
        String jobId,
        JobStatus status,
        Instant createdAt,
        Instant completedAt,
        String errorMessage
) {}
