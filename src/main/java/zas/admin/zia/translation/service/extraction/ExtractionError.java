package zas.admin.zia.translation.service.extraction;

record ExtractionError(String code, String message) {

    static final String UNREADABLE_DOCUMENT = "UNREADABLE_DOCUMENT";
    static final String EXTRACTION_FAILED = "EXTRACTION_FAILED";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";
}
