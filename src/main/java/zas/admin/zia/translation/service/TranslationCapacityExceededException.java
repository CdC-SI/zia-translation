package zas.admin.zia.translation.service;

/**
 * Thrown when a translation job cannot be queued because the translation executor is full.
 * Mapped to HTTP 429 with a {@code Retry-After} header.
 */
public class TranslationCapacityExceededException extends RuntimeException {

    public TranslationCapacityExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
