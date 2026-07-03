package com.xsdvalidator.api.dto;

public record XjbFallbackNameDto(
        String xsdName,
        String javaName,
        String bindingKind
) {
}
