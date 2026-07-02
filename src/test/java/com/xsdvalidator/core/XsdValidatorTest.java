package com.xsdvalidator.core;

import com.xsdvalidator.core.model.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class XsdValidatorTest {

    @Autowired
    private SchemaRegistry schemaRegistry;

    @Autowired
    private XsdValidator xsdValidator;

    @BeforeEach
    void requireDemoSchema() {
        assertTrue(schemaRegistry.exists("demo"), "demo.xsd must be present in resources/schemas");
    }

    @Test
    void validSamplePassesValidation() throws IOException {
        ValidationResult result = validateResource("valid-sample.xml");
        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void missingRequiredElementFails() throws IOException {
        ValidationResult result = validateResource("invalid-required.xml");
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.line() > 0));
    }

    @Test
    void wrongElementOrderFails() throws IOException {
        ValidationResult result = validateResource("invalid-order.xml");
        assertFalse(result.valid());
    }

    @Test
    void invalidEnumFails() throws IOException {
        ValidationResult result = validateResource("invalid-enum.xml");
        assertFalse(result.valid());
    }

    private ValidationResult validateResource(String resourceName) throws IOException {
        try (InputStream xml = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (xml == null) {
                throw new IOException("Resource not found: " + resourceName);
            }
            return xsdValidator.validate(schemaRegistry.getSchema("demo"), "demo", xml);
        }
    }
}
