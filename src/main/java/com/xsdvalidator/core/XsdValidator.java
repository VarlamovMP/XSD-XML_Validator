package com.xsdvalidator.core;

import com.xsdvalidator.core.model.ValidationError;
import com.xsdvalidator.core.model.ValidationResult;
import com.xsdvalidator.core.model.ValidationSeverity;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Component
public class XsdValidator {

    public ValidationResult validate(Schema schema, String schemaId, InputStream xmlStream) {
        long startedAt = System.currentTimeMillis();
        List<ValidationError> errors = new ArrayList<>();

        try {
            Validator validator = schema.newValidator();
            validator.setErrorHandler(new CollectingErrorHandler(errors));
            validator.validate(new StreamSource(xmlStream));
        } catch (SAXException exception) {
            errors.add(new ValidationError(0, 0, exception.getMessage(), ValidationSeverity.FATAL));
        } catch (IOException exception) {
            errors.add(new ValidationError(0, 0, exception.getMessage(), ValidationSeverity.FATAL));
        }

        long durationMs = System.currentTimeMillis() - startedAt;
        return ValidationResult.of(schemaId, errors, durationMs);
    }

    public ValidationResult validateFromXsd(InputStream xsdStream, String schemaId, InputStream xmlStream)
            throws SAXException {
        var factory = javax.xml.validation.SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Schema schema = factory.newSchema(new StreamSource(xsdStream));
        return validate(schema, schemaId, xmlStream);
    }
}
