package com.xsdvalidator.api.dto;

import java.util.List;

public record SchemaXjbResponse(
        String schemaId,
        String packageName,
        String rootElement,
        String xjb,
        int vocabularyHits,
        int fallbackHits,
        List<String> unknownNames,
        List<XjbFallbackNameDto> fallbackNames
) {
}