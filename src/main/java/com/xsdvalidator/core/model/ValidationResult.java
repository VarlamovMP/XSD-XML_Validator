package com.xsdvalidator.core.model;

import java.util.List;

public record ValidationResult(
        boolean valid,
        String schemaId,
        List<ValidationError> errors,
        long durationMs
) {
    public static ValidationResult of(String schemaId, List<ValidationError> errors, long durationMs) {
        boolean valid = errors.stream()
                .noneMatch(error -> error.severity() == ValidationSeverity.ERROR
                        || error.severity() == ValidationSeverity.FATAL);
        return new ValidationResult(valid, schemaId, List.copyOf(errors), durationMs);
    }
}
