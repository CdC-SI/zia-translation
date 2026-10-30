package zas.admin.zia.translation.service.extraction;

public enum ExtractionStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED;

    public boolean isFinished() {
        return this == SUCCEEDED || this == FAILED;
    }
}
