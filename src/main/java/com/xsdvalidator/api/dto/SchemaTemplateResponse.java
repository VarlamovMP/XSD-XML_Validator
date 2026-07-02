package com.xsdvalidator.api.dto;

public record SchemaTemplateResponse(
        String schemaId,
        String rootElement,
        String xml
) {
}
