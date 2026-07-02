package com.xsdvalidator.core;

import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryResourceResolver implements LSResourceResolver {

    private final Map<String, byte[]> files = new ConcurrentHashMap<>();

    public void put(String fileName, byte[] content) {
        files.put(fileName, content);
    }

    @Override
    public LSInput resolveResource(
            String type,
            String namespaceURI,
            String publicId,
            String systemId,
            String baseURI
    ) {
        if (systemId == null || systemId.isBlank()) {
            return null;
        }

        String fileName = extractFileName(systemId);
        byte[] content = files.get(fileName);
        if (content == null) {
            return null;
        }

        return new InputImpl(publicId, systemId, content);
    }

    static String extractFileName(String systemId) {
        String normalized = systemId.replace("\\", "/");
        int slashIndex = normalized.lastIndexOf('/');
        return slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
    }

    private static final class InputImpl implements LSInput {

        private final String publicId;
        private final String systemId;
        private final byte[] content;

        private InputImpl(String publicId, String systemId, byte[] content) {
            this.publicId = publicId;
            this.systemId = systemId;
            this.content = content;
        }

        @Override
        public Reader getCharacterStream() {
            return new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8);
        }

        @Override
        public void setCharacterStream(Reader characterStream) {
        }

        @Override
        public InputStream getByteStream() {
            return new ByteArrayInputStream(content);
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
