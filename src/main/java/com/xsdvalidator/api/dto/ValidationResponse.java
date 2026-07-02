package com.xsdvalidator.api.dto;

import com.xsdvalidator.core.model.ValidationError;
import com.xsdvalidator.core.model.ValidationResult;

import java.util.List;

public record ValidationResponse(
        boolean valid,
        String schemaId,
        List<ValidationErrorDto> errors,
        long durationMs
) {
    public static ValidationResponse from(ValidationResult result) {
        List<ValidationErrorDto> errors = result.errors().stream()
                .map(ValidationErrorDto::from)
                .toList();
        return new ValidationResponse(result.valid(), result.schemaId(), errors, result.durationMs());
    }
}
