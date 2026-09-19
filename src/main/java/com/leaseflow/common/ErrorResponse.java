package com.leaseflow.common;

import java.time.OffsetDateTime;
import java.util.List;

public record ErrorResponse(String code, String message, OffsetDateTime timestamp,
                            List<FieldError> errors) {

    public ErrorResponse(String code, String message) {
        this(code, message, OffsetDateTime.now(), List.of());
    }

    public ErrorResponse(String code, String message, List<FieldError> errors) {
        this(code, message, OffsetDateTime.now(), errors);
    }

    public record FieldError(String field, String reason) {
    }
}
