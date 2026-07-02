package com.xsdvalidator.core;

public class SchemaAlreadyExistsException extends RuntimeException {

    private final String schemaId;

    public SchemaAlreadyExistsException(String schemaId) {
        super("Schema already exists: " + schemaId);
        this.schemaId = schemaId;
    }

    public String getSchemaId() {
        return schemaId;
    }
}
