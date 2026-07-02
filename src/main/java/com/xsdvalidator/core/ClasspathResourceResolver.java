package com.xsdvalidator.core;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public class ClasspathResourceResolver implements LSResourceResolver {

    private final ResourceLoader resourceLoader;
    private final String schemasBasePath;

    public ClasspathResourceResolver(ResourceLoader resourceLoader, String schemasBasePath) {
        this.resourceLoader = resourceLoader;
        this.schemasBasePath = normalizeBasePath(schemasBasePath);
    }

    @Override
    public LSInput resolveResource(
            String type,
            String namespaceURI,
            String publicId,
            String systemId,
            String baseURI
    ) {
        Resource resource = resolveResourceLocation(systemId, baseURI);
        if (resource == null || !resource.exists()) {
            return null;
        }

        try {
            return new InputImpl(publicId, systemId, resource.getInputStream());
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read XSD resource: " + systemId, exception);
        }
    }

    private Resource resolveResourceLocation(String systemId, String baseURI) {
        if (systemId == null || systemId.isBlank()) {
            return resourceLoader.getResource(schemasBasePath);
        }

        if (systemId.startsWith("classpath:")) {
            return resourceLoader.getResource(systemId);
        }

        if (baseURI != null && !baseURI.isBlank()) {
            try {
                URI resolved = URI.create(baseURI).resolve(systemId);
                Resource resource = resourceLoader.getResource(resolved.toString());
                if (resource.exists()) {
                    return resource;
                }
            } catch (IllegalArgumentException ignored) {
                // try other resolution strategies
            }
        }

        String normalized = systemId.replace("\\", "/");
        Resource direct = resourceLoader.getResource(schemasBasePath + "/" + normalized);
        if (direct.exists()) {
            return direct;
        }

        String fileName = InMemoryResourceResolver.extractFileName(systemId);
        try {
            Resource[] matches = new PathMatchingResourcePatternResolver(resourceLoader)
                    .getResources(schemasBasePath + "/**/" + fileName);
            if (matches.length > 0) {
                return matches[0];
            }
        } catch (IOException ignored) {
            return null;
        }

        return null;
    }

    private static String normalizeBasePath(String schemasDirectory) {
        String path = schemasDirectory;
        if (path.startsWith("classpath:")) {
            path = path.substring("classpath:".length());
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return "classpath:" + path;
    }

    private static final class InputImpl implements LSInput {

        private final String publicId;
        private final String systemId;
        private final InputStream inputStream;

        private InputImpl(String publicId, String systemId, InputStream inputStream) {
            this.publicId = publicId;
            this.systemId = systemId;
            this.inputStream = inputStream;
        }

        @Override
        public Reader getCharacterStream() {
            return new InputStreamReader(inputStream, StandardCharsets.UTF_8);
        }

        @Override
        public void setCharacterStream(Reader characterStream) {
        }

        @Override
        public InputStream getByteStream() {
            return inputStream;
        }

        @Override
        public void setByteStream(InputStream byteStream) {
        }

        @Override
        public String getStringData() {
            return null;
        }

        @Override
        public void setStringData(String stringData) {
        }

        @Override
        public String getSystemId() {
            return systemId;
        }

        @Override
        public void setSystemId(String systemId) {
        }

        @Override
        public String getPublicId() {
            return publicId;
        }

        @Override
        public void setPublicId(String publicId) {
        }

        @Override
        public String getBaseURI() {
            return null;
        }

        @Override
        public void setBaseURI(String baseURI) {
        }

        @Override
        public String getEncoding() {
            return StandardCharsets.UTF_8.name();
        }

        @Override
        public void setEncoding(String encoding) {
        }

        @Override
        public boolean getCertifiedText() {
            return false;
        }

        @Override
        public void setCertifiedText(boolean certifiedText) {
        }
    }
}
