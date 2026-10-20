package zas.admin.zia.translation.service.invoice;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import zas.admin.zia.translation.service.InvalidDocumentException;
import zas.admin.zia.translation.service.dto.ErrorResponse;

import java.time.Instant;

/**
 * Error statuses of the invoice extraction contract; other exceptions are handled by the global handler.
 */
@RestControllerAdvice(assignableTypes = InvoiceExtractionController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class InvoiceExtractionExceptionHandler {

    @ExceptionHandler(InvalidDocumentException.class)
    ResponseEntity<ErrorResponse> handleInvalidDocument(InvalidDocumentException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        return build(HttpStatus.BAD_REQUEST, "Missing required header: " + ex.getHeaderName());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException ex) {
        return build(HttpStatus.BAD_REQUEST, "Missing required part: " + ex.getRequestPartName());
    }

    @ExceptionHandler(InvoiceExtractionService.DocumentTooLargeException.class)
    ResponseEntity<ErrorResponse> handleTooLarge(InvoiceExtractionService.DocumentTooLargeException ex) {
        return build(HttpStatus.CONTENT_TOO_LARGE, ex.getMessage());
    }

    @ExceptionHandler(InvoiceExtractionService.ExtractionCapacityExceededException.class)
    ResponseEntity<ErrorResponse> handleCapacityExceeded(InvoiceExtractionService.ExtractionCapacityExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "30")
                .body(new ErrorResponse(HttpStatus.TOO_MANY_REQUESTS.value(), ex.getMessage(), Instant.now()));
    }

    private static ResponseEntity<ErrorResponse> build(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(status.value(), message, Instant.now()));
    }
}
