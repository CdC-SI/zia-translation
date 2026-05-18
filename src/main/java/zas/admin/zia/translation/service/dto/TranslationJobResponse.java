package zas.admin.zia.translation.service.dto;

import zas.admin.zia.translation.service.job.JobStatus;

public record TranslationJobResponse(
        String jobId,
        JobStatus status
) {}
