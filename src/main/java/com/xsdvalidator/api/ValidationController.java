package com.xsdvalidator.api;

import com.xsdvalidator.config.XmlValidatorProperties;
import com.xsdvalidator.core.SchemaAlreadyExistsException;
import com.xsdvalidator.core.SchemaNotFoundException;
import com.xsdvalidator.core.SchemaRegistry;
import com.xsdvalidator.core.TemplateGenerationException;
import com.xsdvalidator.core.XmlFormatException;
import com.xsdvalidator.core.XmlFormatter;
import com.xsdvalidator.core.XjbBindingGenerator;
import com.xsdvalidator.core.XjbGenerationException;
import com.xsdvalidator.core.XsdTemplateGenerator;
import com.xsdvalidator.core.XsdValidator;
import com.xsdvalidator.core.model.ValidationResult;
import com.xsdvalidator.api.dto.ErrorResponse;
import com.xsdvalidator.api.dto.SchemaTemplateResponse;
import com.xsdvalidator.api.dto.SchemaXjbResponse;
import com.xsdvalidator.api.dto.XjbFallbackNameDto;
import com.xsdvalidator.api.dto.SchemaUploadResponse;
import com.xsdvalidator.api.dto.ValidationResponse;
import com.xsdvalidator.core.model.SchemaInfo;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ValidationController {

    private final SchemaRegistry schemaRegistry;
    private final XsdValidator xsdValidator;
    private final XsdTemplateGenerator templateGenerator;
    private final XjbBindingGenerator xjbBindingGenerator;
    private final XmlFormatter xmlFormatter;
    private final XmlValidatorProperties properties;

    public ValidationController(
            SchemaRegistry schemaRegistry,
            XsdValidator xsdValidator,
            XsdTemplateGenerator templateGenerator,
            XjbBindingGenerator xjbBindingGenerator,
            XmlFormatter xmlFormatter,
            XmlValidatorProperties properties
    ) {
        this.schemaRegistry = schemaRegistry;
        this.xsdValidator = xsdValidator;
        this.templateGenerator = templateGenerator;
        this.xjbBindingGenerator = xjbBindingGenerator;
        this.xmlFormatter = xmlFormatter;
        this.properties = properties;
    }

    @GetMapping("/schemas")
    public List<SchemaInfo> listSchemas() {
        return schemaRegistry.listSchemas();
    }

    @GetMapping("/schemas/template")
    public SchemaTemplateResponse generateTemplate(@RequestParam("schemaId") String schemaId) {
        XsdTemplateGenerator.GeneratedTemplate template = templateGenerator.generate(schemaId);
        return new SchemaTemplateResponse(template.schemaId(), template.rootElement(), template.xml());
    }

    @GetMapping("/schemas/xjb")
    public SchemaXjbResponse generateXjb(
            @RequestParam("schemaId") String schemaId,
            @RequestParam(value = "package", required = false) String packageName
    ) {
        XjbBindingGenerator.GeneratedXjb generated = xjbBindingGenerator.generate(schemaId, packageName);
        return new SchemaXjbResponse(
                generated.schemaId(),
                generated.packageName(),
                generated.rootElement(),
                generated.xjb(),
                generated.vocabularyHits(),
                generated.fallbackHits(),
                generated.unknownNames(),
                generated.fallbackNames()
        );
    }

    @PostMapping(
            value = "/xml/format",
            consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.TEXT_PLAIN_VALUE},
            produces = MediaType.APPLICATION_XML_VALUE
    )
    public ResponseEntity<byte[]> formatXml(@RequestBody byte[] xml) {
        validateXmlSize(xml);
        String formatted = xmlFormatter.format(xml);
        return ResponseEntity.ok()
                .contentType(new MediaType("application", "xml", StandardCharsets.UTF_8))
                .body(formatted.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping(value = "/schemas", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SchemaUploadResponse uploadSchemas(@RequestPart("file") MultipartFile[] files) throws IOException, SAXException {
        if (files == null || files.length == 0) {
            throw new InvalidRequestException("At least one XSD file is required");
        }

        Map<String, byte[]> filesByName = new LinkedHashMap<>();
        for (MultipartFile file : files) {
            String fileName = extractFileName(file);
            filesByName.put(fileName, readLimited(file));
        }

        SchemaRegistry.SchemaUploadResult result = schemaRegistry.registerSchemas(filesByName);
        if (result.registered().isEmpty()) {
            throw new SAXException(formatUploadFailures(result.warnings()));
        }

        return new SchemaUploadResponse(result.registered(), result.warnings());
    }

    private static String extractFileName(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            throw new InvalidRequestException("XSD file name is required");
        }
        int slashIndex = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        return slashIndex >= 0 ? fileName.substring(slashIndex + 1) : fileName;
    }

    private static String formatUploadFailures(List<String> warnings) {
        if (warnings.isEmpty()) {
            return "Failed to register XSD schemas";
        }
        return String.join("; ", warnings);
    }

    @PostMapping(
            value = "/validate",
            consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.TEXT_PLAIN_VALUE}
    )
    public ValidationResponse validate(
            @RequestParam("schemaId") String schemaId,
            @RequestBody byte[] xml
    ) {
        return validateXml(schemaId, xml);
    }

    @PostMapping(
            value = "/validate/{schemaId}",
            consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.TEXT_PLAIN_VALUE}
    )
    public ValidationResponse validateByPath(
            @PathVariable String schemaId,
            @RequestBody byte[] xml
    ) {
        return validateXml(schemaId, xml);
    }

    private ValidationResponse validateXml(String schemaId, byte[] xml) {
        validateXmlSize(xml);
        ValidationResult result = xsdValidator.validate(
                schemaRegistry.getSchema(schemaId),
                schemaId,
                new ByteArrayInputStream(xml)
        );
        return ValidationResponse.from(result);
    }

    @PostMapping(value = "/validate/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ValidationResponse validateUpload(
            @RequestPart("xml") MultipartFile xmlFile,
            @RequestPart("schemaId") String schemaId
    ) throws IOException {
        byte[] xml = readLimited(xmlFile);
        ValidationResult result = xsdValidator.validate(
                schemaRegistry.getSchema(schemaId),
                schemaId,
                new ByteArrayInputStream(xml)
        );
        return ValidationResponse.from(result);
    }

    @ExceptionHandler(XmlFormatException.class)
    public ResponseEntity<ErrorResponse> handleXmlFormat(XmlFormatException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("XML_FORMAT_FAILED", exception.getMessage()));
    }

    @ExceptionHandler(TemplateGenerationException.class)
    public ResponseEntity<ErrorResponse> handleTemplateGeneration(TemplateGenerationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("TEMPLATE_GENERATION_FAILED", exception.getMessage()));
    }

    @ExceptionHandler(XjbGenerationException.class)
    public ResponseEntity<ErrorResponse> handleXjbGeneration(XjbGenerationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("XJB_GENERATION_FAILED", exception.getMessage()));
    }

    @ExceptionHandler(SchemaNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSchemaNotFound(SchemaNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("SCHEMA_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(SchemaAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleSchemaAlreadyExists(SchemaAlreadyExistsException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("SCHEMA_ALREADY_EXISTS", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(InvalidRequestException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(SAXException.class)
    public ResponseEntity<ErrorResponse> handleSaxException(SAXException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_XSD", exception.getMessage()));
    }

    private void validateXmlSize(byte[] xml) {
        if (xml.length > properties.getMaxXmlSizeBytes()) {
            throw new InvalidRequestException(
                    "XML exceeds max size: " + xml.length + " bytes (limit " + properties.getMaxXmlSizeBytes() + ")"
            );
        }
    }

    private byte[] readLimited(MultipartFile file) throws IOException {
        byte[] content = file.getBytes();
        if (content.length > properties.getMaxXmlSizeBytes()) {
            throw new InvalidRequestException(
                    "File exceeds max size: " + content.length + " bytes (limit " + properties.getMaxXmlSizeBytes() + ")"
            );
        }
        return content;
    }

    static class InvalidRequestException extends RuntimeException {
        InvalidRequestException(String message) {
            super(message);
        }
    }
}
