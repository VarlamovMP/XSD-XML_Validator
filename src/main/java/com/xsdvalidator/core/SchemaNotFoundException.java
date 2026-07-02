package com.xsdvalidator.core;

public class SchemaNotFoundException extends RuntimeException {

    private final String schemaId;

    public SchemaNotFoundException(String schemaId) {
        super("Schema not found: " + schemaId);
        this.schemaId = schemaId;
    }

    public String getSchemaId() {
        return schemaId;
    }
}
