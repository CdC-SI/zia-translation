package zas.admin.zia.translation.service.extraction;

enum ExtractionStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED;

    boolean isFinished() {
        return this == SUCCEEDED || this == FAILED;
    }
}
