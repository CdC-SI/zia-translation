package zas.admin.zia.translation.service;

/**
 * The model stopped before finishing its answer (finish reason {@code length}: the max-tokens cap was reached).
 * Its partial or empty content must not be used as if it were the full answer.
 */
public class IncompleteGenerationException extends RuntimeException {

    public IncompleteGenerationException(String message) {
        super(message);
    }
}
