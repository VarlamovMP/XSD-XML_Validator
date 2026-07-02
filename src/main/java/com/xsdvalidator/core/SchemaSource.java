package com.xsdvalidator.core;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public record SchemaSource(
        String systemId,
        byte[] content,
        String fileName
) {
    public boolean isUploaded() {
        return content != null;
    }

    public InputStream openStream(ResourceLoader resourceLoader) throws IOException {
        if (isUploaded()) {
            return new ByteArrayInputStream(content);
        }
        Resource resource = resourceLoader.getResource(systemId);
        if (!resource.exists()) {
            throw new IOException("Schema resource not found: " + systemId);
        }
        return resource.getInputStream();
    }

    public String baseUri(ResourceLoader resourceLoader) throws IOException {
        if (isUploaded()) {
            return "file:///" + fileName.replace("\\", "/");
        }
        return resourceLoader.getResource(systemId).getURI().toString();
    }
}
