package zas.admin.zia.translation.service.extraction;

public record ExtractionError(String code, String message) {

    public static final String UNREADABLE_DOCUMENT = "UNREADABLE_DOCUMENT";
    public static final String EXTRACTION_FAILED = "EXTRACTION_FAILED";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
}
