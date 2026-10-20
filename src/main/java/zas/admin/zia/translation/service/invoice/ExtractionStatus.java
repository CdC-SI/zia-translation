package zas.admin.zia.translation.service.invoice;

enum ExtractionStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED;

    boolean isFinished() {
        return this == SUCCEEDED || this == FAILED;
    }
}
