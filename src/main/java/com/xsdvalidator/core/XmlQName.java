package com.xsdvalidator.core;

import org.w3c.dom.Element;

import java.util.Set;

record XmlQName(String namespace, String localName) {

    private static final String XS_NS = "http://www.w3.org/2001/XMLSchema";
    private static final Set<String> BUILTIN_TYPE_NAMES = Set.of(
            "string", "boolean", "decimal", "float", "double", "duration", "dateTime", "time", "date",
            "gYearMonth", "gYear", "gMonthDay", "gDay", "gMonth", "hexBinary", "base64Binary", "anyURI",
            "QName", "NOTATION", "normalizedString", "token", "language", "NMTOKEN", "NMTOKENS", "Name",
            "NCName", "ID", "IDREF", "IDREFS", "ENTITY", "ENTITIES", "integer", "nonPositiveInteger",
            "negativeInteger", "long", "int", "short", "byte", "nonNegativeInteger", "unsignedLong",
            "unsignedInt", "unsignedShort", "unsignedByte", "positiveInteger"
    );

    static XmlQName parse(String raw, Element context) {
        if (raw == null || raw.isBlank()) {
            throw new TemplateGenerationException("Empty QName");
        }

        int colonIndex = raw.indexOf(':');
        if (colonIndex < 0) {
            if (BUILTIN_TYPE_NAMES.contains(raw)) {
                return new XmlQName(XS_NS, raw);
            }
            return new XmlQName(targetNamespace(context), raw);
        }

        String prefix = raw.substring(0, colonIndex);
        String localName = raw.substring(colonIndex + 1);
        String namespace = context.lookupNamespaceURI(prefix);
        if (namespace == null && "xs".equals(prefix)) {
            namespace = XS_NS;
        }
        if (namespace == null) {
            namespace = "";
        }
        return new XmlQName(namespace, localName);
    }

    private static String targetNamespace(Element context) {
        Element schema = schemaElement(context);
        return schema != null ? schema.getAttribute("targetNamespace") : "";
    }

    static Element schemaElement(Element context) {
        Element current = context;
        while (current != null) {
            if (isXs(current) && "schema".equals(current.getLocalName())) {
                return current;
            }
            current = current.getParentNode() instanceof Element parent ? parent : null;
        }
        return null;
    }

    static boolean isXs(Element element) {
        return "http://www.w3.org/2001/XMLSchema".equals(element.getNamespaceURI());
    }
}
