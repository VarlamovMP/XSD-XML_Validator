package com.xsdvalidator.core.model;

public record ValidationError(
        int line,
        int column,
        String message,
        ValidationSeverity severity
) {
}
