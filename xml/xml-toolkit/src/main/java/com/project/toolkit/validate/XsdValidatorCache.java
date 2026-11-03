package com.project.toolkit.validate;

import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.File;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads each XSD's compiled {@link Schema} once and reuses it for every validation call,
 * since compiling a schema is expensive and the toolkit validates thousands of files per run.
 */
public final class XsdValidatorCache {

    private static final Map<Path, Schema> CACHE = new ConcurrentHashMap<>();

    private XsdValidatorCache() {
    }

    public static Schema get(Path xsdPath) {
        return CACHE.computeIfAbsent(xsdPath, p -> {
            try {
                SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                return factory.newSchema(new File(p.toString()));
            } catch (SAXException e) {
                throw new IllegalArgumentException("Invalid XSD: " + p + " -- " + e.getMessage(), e);
            }
        });
    }

    public static ValidationResult validate(Path xsdPath, Path xmlPath) {
        return validate(xsdPath, new StreamSource(xmlPath.toFile()));
    }

    /** Same as {@link #validate(Path, Path)} but for an XML document that only exists in memory
     * (e.g. a DTCC supplement fragment generated for A7, validated against its own XSD before
     * being embedded in the outer document) -- avoids a throwaway temp file for that check. */
    public static ValidationResult validateString(Path xsdPath, String xml) {
        return validate(xsdPath, new StreamSource(new StringReader(xml)));
    }

    private static ValidationResult validate(Path xsdPath, StreamSource source) {
        Schema schema = get(xsdPath);
        Validator validator = schema.newValidator();
        CollectingErrorHandler handler = new CollectingErrorHandler();
        validator.setErrorHandler(handler);
        try {
            validator.validate(source);
        } catch (Exception e) {
            return ValidationResult.fail(e.getMessage());
        }
        if (handler.hasErrors()) {
            return ValidationResult.fail(handler.firstMessage());
        }
        return ValidationResult.ok();
    }

    public record ValidationResult(boolean valid, String reason) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    private static final class CollectingErrorHandler implements org.xml.sax.ErrorHandler {
        private String first;
        private int count;

        @Override
        public void warning(org.xml.sax.SAXParseException exception) {
        }

        @Override
        public void error(org.xml.sax.SAXParseException exception) {
            record(exception);
        }

        @Override
        public void fatalError(org.xml.sax.SAXParseException exception) {
            record(exception);
        }

        private void record(org.xml.sax.SAXParseException exception) {
            count++;
            if (first == null) {
                first = "line " + exception.getLineNumber() + ": " + exception.getMessage();
            }
        }

        boolean hasErrors() {
            return count > 0;
        }

        String firstMessage() {
            return first;
        }
    }
}
