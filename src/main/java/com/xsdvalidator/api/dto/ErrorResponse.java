package com.xsdvalidator.api.dto;

public record ErrorResponse(
        String error,
        String message
) {
}
