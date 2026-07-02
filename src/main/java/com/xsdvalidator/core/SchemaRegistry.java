package com.xsdvalidator.core;

import com.xsdvalidator.config.XmlValidatorProperties;
import com.xsdvalidator.core.model.SchemaInfo;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SchemaRegistry {

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistry.class);

    private final XmlValidatorProperties properties;
    private final ResourceLoader resourceLoader;
    private final Map<String, Schema> schemas = new ConcurrentHashMap<>();
    private final Map<String, SchemaInfo> schemaInfoById = new ConcurrentHashMap<>();
    private final Map<String, String> classpathSystemIdBySchemaId = new ConcurrentHashMap<>();
    private final Map<String, byte[]> uploadedContentBySchemaId = new ConcurrentHashMap<>();
    private final InMemoryResourceResolver uploadedResourceResolver = new InMemoryResourceResolver();

    public SchemaRegistry(XmlValidatorProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    void loadSchemas() throws IOException {
        String classpathPattern = toClasspathPattern(properties.getSchemasDirectory());
        Resource[] resources = new PathMatchingResourcePatternResolver(resourceLoader)
                .getResources(classpathPattern);

        if (resources.length == 0) {
            log.warn("No XSD schemas found at {}", classpathPattern);
            return;
        }

        SchemaFactory factory = createSchemaFactory();

        List<String> failures = new ArrayList<>();
        int skippedDuplicates = 0;

        for (Resource resource : resources) {
            String fileName = resource.getFilename();
            if (fileName == null || !fileName.endsWith(".xsd")) {
                continue;
            }

            String schemaId = toClasspathSchemaId(resource, fileName);
            if (schemas.containsKey(schemaId)) {
                skippedDuplicates++;
                log.warn("Skipping duplicate schema id={} from {}", schemaId, resource.getDescription());
                continue;
            }

            try {
                String systemId = resource.getURI().toString();
                Schema schema = factory.newSchema(new StreamSource(resource.getInputStream(), systemId));
                schemas.put(schemaId, schema);
                schemaInfoById.put(schemaId, new SchemaInfo(schemaId, fileName, "Schema " + schemaId));
                classpathSystemIdBySchemaId.put(schemaId, systemId);
                log.info("Loaded XSD schema: id={}, file={}", schemaId, fileName);
            } catch (SAXException exception) {
                failures.add(schemaId + ": " + exception.getMessage());
                log.warn("Failed to compile XSD schema id={}: {}", schemaId, exception.getMessage());
            }
        }

        if (schemas.isEmpty()) {
            throw new IllegalStateException(
                    "No XSD schemas could be compiled from " + classpathPattern
                            + (failures.isEmpty() ? "" : ": " + String.join("; ", failures))
            );
        }

        if (!failures.isEmpty()) {
            log.warn("Skipped {} XSD schema(s) due to compilation errors", failures.size());
        }
        if (skippedDuplicates > 0) {
            log.warn("Skipped {} XSD schema(s) with duplicate ids", skippedDuplicates);
        }
    }

    private static String toClasspathSchemaId(Resource resource, String fileName) throws IOException {
        String uri = resource.getURI().toString().replace("\\", "/");
        int schemasMarker = uri.indexOf("/schemas/");
        if (schemasMarker >= 0) {
            String relativePath = uri.substring(schemasMarker + "/schemas/".length());
            if (relativePath.endsWith(".xsd")) {
                return relativePath.substring(0, relativePath.length() - 4);
            }
        }
        return toSchemaId(fileName);
    }

    public Schema getSchema(String schemaId) {
        Schema schema = schemas.get(schemaId);
        if (schema == null) {
            throw new SchemaNotFoundException(schemaId);
        }
        return schema;
    }

    public List<SchemaInfo> listSchemas() {
        return schemaInfoById.values().stream()
                .sorted(Comparator.comparing(SchemaInfo::id))
                .toList();
    }

    public boolean exists(String schemaId) {
        return schemas.containsKey(schemaId);
    }

    public SchemaSource getSchemaSource(String schemaId) {
        SchemaInfo info = schemaInfoById.get(schemaId);
        if (info == null) {
            throw new SchemaNotFoundException(schemaId);
        }

        byte[] uploaded = uploadedContentBySchemaId.get(schemaId);
        if (uploaded != null) {
            return new SchemaSource(info.fileName(), uploaded, info.fileName());
        }

        String systemId = classpathSystemIdBySchemaId.get(schemaId);
        if (systemId != null) {
            return new SchemaSource(systemId, null, info.fileName());
        }

        throw new SchemaNotFoundException(schemaId);
    }

    public InMemoryResourceResolver uploadedResourceResolver() {
        return uploadedResourceResolver;
    }

    public ClasspathResourceResolver classpathResourceResolver() {
        return new ClasspathResourceResolver(resourceLoader, properties.getSchemasDirectory());
    }

    public SchemaInfo registerSchema(String fileName, byte[] content) throws SAXException {
        SchemaUploadResult result = registerSchemas(Map.of(fileName, content));
        if (result.registered().isEmpty()) {
            String message = result.warnings().isEmpty()
                    ? "Failed to register XSD"
                    : String.join("; ", result.warnings());
            throw new SAXException(message);
        }
        return result.registered().get(0);
    }

    public SchemaUploadResult registerSchemas(Map<String, byte[]> filesByName) {
        validateUploadFiles(filesByName);

        for (Map.Entry<String, byte[]> entry : filesByName.entrySet()) {
            uploadedResourceResolver.put(entry.getKey(), entry.getValue());
        }

        SchemaFactory factory = createSchemaFactory();
        List<SchemaInfo> registered = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (Map.Entry<String, byte[]> entry : filesByName.entrySet()) {
            String fileName = entry.getKey();
            byte[] content = entry.getValue();
            String schemaId = toSchemaId(fileName);

            if (schemas.containsKey(schemaId)) {
                warnings.add(fileName + ": схема «" + schemaId + "» уже существует");
                continue;
            }

            try {
                Schema schema = factory.newSchema(new StreamSource(
                        new ByteArrayInputStream(content),
                        fileName
                ));
                schemas.put(schemaId, schema);
                SchemaInfo schemaInfo = new SchemaInfo(schemaId, fileName, "Загружено через UI");
                schemaInfoById.put(schemaId, schemaInfo);
                uploadedContentBySchemaId.put(schemaId, content);
                registered.add(schemaInfo);
                log.info("Registered uploaded XSD schema: id={}, file={}", schemaId, fileName);
            } catch (SAXException exception) {
                warnings.add(fileName + ": " + exception.getMessage());
            }
        }

        return new SchemaUploadResult(registered, warnings);
    }

    private void validateUploadFiles(Map<String, byte[]> filesByName) {
        if (filesByName == null || filesByName.isEmpty()) {
            throw new IllegalArgumentException("At least one XSD file is required");
        }

        for (Map.Entry<String, byte[]> entry : filesByName.entrySet()) {
            String fileName = entry.getKey();
            byte[] content = entry.getValue();

            if (fileName == null || fileName.isBlank()) {
                throw new IllegalArgumentException("XSD file name is required");
            }
            if (!fileName.endsWith(".xsd")) {
                throw new IllegalArgumentException("File must have .xsd extension: " + fileName);
            }
            if (content == null || content.length == 0) {
                throw new IllegalArgumentException("XSD content is empty: " + fileName);
            }
            if (content.length > properties.getMaxXmlSizeBytes()) {
                throw new IllegalArgumentException(
                        "XSD exceeds max size: " + fileName + " (" + content.length + " bytes, limit "
                                + properties.getMaxXmlSizeBytes() + ")"
                );
            }
        }
    }

    private SchemaFactory createSchemaFactory() {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setResourceResolver(new ChainingResourceResolver(
                uploadedResourceResolver,
                new ClasspathResourceResolver(resourceLoader, properties.getSchemasDirectory())
        ));
        return factory;
    }

    private static String toSchemaId(String fileName) {
        return fileName.substring(0, fileName.length() - 4);
    }

    public record SchemaUploadResult(
            List<SchemaInfo> registered,
            List<String> warnings
    ) {
    }

    private static String toClasspathPattern(String schemasDirectory) {
        String path = schemasDirectory;
        if (path.startsWith("classpath:")) {
            path = path.substring("classpath:".length());
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return "classpath:" + path + "/**/*.xsd";
    }
}
