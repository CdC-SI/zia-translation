package zas.admin.zia.translation.service.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import zas.admin.zia.translation.service.InvalidDocumentException;
import zas.admin.zia.translation.service.TranslationProcessingException;
import zas.admin.zia.translation.service.dto.ErrorResponse;

import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(InvalidDocumentException.class)
    ResponseEntity<ErrorResponse> handleInvalidDocument(InvalidDocumentException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(TranslationProcessingException.class)
    ResponseEntity<ErrorResponse> handleTranslationProcessing(TranslationProcessingException ex) {
        return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, "Missing required parameter: " + ex.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Class<?> requiredType = ex.getRequiredType();
        if (requiredType != null && requiredType.isEnum()) {
            String allowedValues = Arrays.stream(requiredType.getEnumConstants())
                    .map(value -> value.toString().toLowerCase(Locale.ROOT))
                    .collect(Collectors.joining(", "));
            return buildResponse(HttpStatus.BAD_REQUEST,
                    "Invalid value '%s' for parameter '%s'. Allowed values: %s"
                            .formatted(ex.getValue(), ex.getName(), allowedValues));
        }
        return buildResponse(HttpStatus.BAD_REQUEST,
                "Invalid value for parameter '%s': %s".formatted(ex.getName(), ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, "File size exceeds the maximum allowed limit.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
    }

    private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(status.value(), message, Instant.now()));
    }
}
