package com.xsdvalidator.api.dto;

import com.xsdvalidator.core.model.ValidationError;

public record ValidationErrorDto(
        int line,
        int column,
        String message,
        String severity
) {
    public static ValidationErrorDto from(ValidationError error) {
        return new ValidationErrorDto(
                error.line(),
                error.column(),
                error.message(),
                error.severity().name()
        );
    }
}
