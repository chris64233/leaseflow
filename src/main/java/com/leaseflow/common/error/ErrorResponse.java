package com.leaseflow.common.error;

import java.time.OffsetDateTime;
import java.util.List;

public record ErrorResponse(
        String code,
        String message,
        OffsetDateTime timestamp,
        List<FieldError> errors
) {
    public record FieldError(String field, String reason) {
    }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, OffsetDateTime.now(), List.of());
    }

    public static ErrorResponse of(String code, String message, List<FieldError> errors) {
        return new ErrorResponse(code, message, OffsetDateTime.now(), errors);
    }
}
