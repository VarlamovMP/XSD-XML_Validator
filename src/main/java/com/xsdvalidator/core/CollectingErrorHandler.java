package com.xsdvalidator.core;

import com.xsdvalidator.core.model.ValidationError;
import com.xsdvalidator.core.model.ValidationSeverity;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import java.util.List;

public class CollectingErrorHandler implements ErrorHandler {

    private final List<ValidationError> errors;

    public CollectingErrorHandler(List<ValidationError> errors) {
        this.errors = errors;
    }

    @Override
    public void warning(SAXParseException exception) {
        errors.add(toError(exception, ValidationSeverity.WARNING));
    }

    @Override
    public void error(SAXParseException exception) {
        errors.add(toError(exception, ValidationSeverity.ERROR));
    }

    @Override
    public void fatalError(SAXParseException exception) {
        errors.add(toError(exception, ValidationSeverity.FATAL));
    }

    private static ValidationError toError(SAXParseException exception, ValidationSeverity severity) {
        return new ValidationError(
                exception.getLineNumber(),
                exception.getColumnNumber(),
                exception.getMessage(),
                severity
        );
    }
}
