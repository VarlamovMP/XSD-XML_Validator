package com.xsdvalidator.api.dto;

import com.xsdvalidator.core.model.SchemaInfo;

import java.util.List;

public record SchemaUploadResponse(
        List<SchemaInfo> registered,
        List<String> warnings
) {
}
