package com.xsdvalidator.core;

import com.xsdvalidator.api.dto.XjbFallbackNameDto;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class XjbBindingGenerator {

    private static final Pattern DATE_SUFFIX = Pattern.compile("(.+)_((\\d{4})-(\\d{2})-(\\d{2}))$");
    private static final String PACKAGE_BASE = "ru.vtb.msa.smkp.model.external.pf";

    private final SchemaRegistry schemaRegistry;
    private final ResourceLoader resourceLoader;
    private final BindingVocabulary vocabulary;

    public XjbBindingGenerator(
            SchemaRegistry schemaRegistry,
            ResourceLoader resourceLoader,
            BindingVocabulary vocabulary
    ) {
        this.schemaRegistry = schemaRegistry;
        this.resourceLoader = resourceLoader;
        this.vocabulary = vocabulary;
    }

    public GeneratedXjb generate(String schemaId, String packageOverride) {
        SchemaSource source = schemaRegistry.getSchemaSource(schemaId);
        Document document = parseDocument(source);
        Element schema = document.getDocumentElement();
        BindingNameResolver names = new BindingNameResolver(vocabulary);

        String fileName = source.fileName();
        if (fileName == null || fileName.isBlank()) {
            fileName = schemaId.substring(schemaId.lastIndexOf('/') + 1) + ".xsd";
        }

        boolean documentMode = isDocumentSchema(schemaId, schema);
        String packageName = packageOverride != null && !packageOverride.isBlank()
                ? packageOverride.trim()
                : derivePackageName(schemaId, fileName);
        String schemaLocation = deriveSchemaLocation(schemaId, fileName);
        String schemaNode = documentMode ? "/xs:schema" : "//xs:schema";

        StringBuilder writer = new StringBuilder(16_384);
        writer.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        writer.append("<jaxb:bindings version=\"2.1\"\n");
        writer.append("               xmlns:jaxb=\"http://java.sun.com/xml/ns/jaxb\"\n");
        writer.append("               xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">\n\n");
        writer.append("    <jaxb:bindings schemaLocation=\"").append(escapeXml(schemaLocation));
        writer.append("\" node=\"").append(schemaNode).append("\">\n");
        writer.append("        <jaxb:schemaBindings>\n");
        writer.append("            <jaxb:package name=\"").append(escapeXml(packageName)).append("\"/>\n");
        writer.append("        </jaxb:schemaBindings>\n\n");

        Element rootElement = documentMode ? findFirstGlobalElement(schema) : null;
        if (documentMode && rootElement == null) {
            throw new XjbGenerationException("Схема не содержит корневого элемента документа");
        }

        String rootName = rootElement != null
                ? rootElement.getAttribute("name")
                : fileName.replaceAll("(?i)\\.xsd$", "");

        if (documentMode) {
            writeDocumentRoot(writer, names, rootElement, 2);
        } else {
            writeGlobalBindings(writer, names, schema, 2);
        }

        writer.append("    </jaxb:bindings>\n");
        writer.append("</jaxb:bindings>\n");

        return new GeneratedXjb(
                schemaId,
                packageName,
                rootName,
                writer.toString(),
                names.vocabularyHits(),
                names.fallbackHits(),
                names.unknownNames(),
                names.fallbackBindings().stream()
                        .map(binding -> new XjbFallbackNameDto(
                                binding.xsdName(),
                                binding.javaName(),
                                binding.bindingKind()
                        ))
                        .toList()
        );
    }

    private void writeDocumentRoot(StringBuilder writer, BindingNameResolver names, Element rootElement, int indent) {
        String rootName = rootElement.getAttribute("name");
        openBinding(writer, "xs:element[@name='" + rootName + "']", indent);

        Element inlineComplexType = findDirectChild(rootElement, "complexType");
        if (inlineComplexType != null) {
            openBinding(writer, "xs:complexType", indent + 1);
            writeLine(writer, indent + 2, "<jaxb:class name=\"" + escapeXml(names.classNameForElement(rootName)) + "\"/>");
            writeContentModel(writer, names, inlineComplexType, indent + 1, "");
            closeBinding(writer, indent + 1);
        }

        closeBinding(writer, indent);
    }

    private void writeGlobalBindings(StringBuilder writer, BindingNameResolver names, Element schema, int indent) {
        for (Element child : xsChildren(schema)) {
            String localName = child.getLocalName();
            String name = child.getAttribute("name");
            if (name.isBlank()) {
                continue;
            }
            switch (localName) {
                case "element" -> writeGlobalElementBinding(writer, names, child, indent);
                case "complexType" -> writeGlobalComplexTypeBinding(writer, names, child, name, indent);
                case "simpleType" -> writeGlobalSimpleTypeBinding(writer, names, child, name, indent);
                case "group" -> writeGlobalGroupBinding(writer, names, child, name, indent);
                default -> {
                }
            }
        }
    }

    private void writeGlobalElementBinding(StringBuilder writer, BindingNameResolver names, Element element, int indent) {
        String name = element.getAttribute("name");
        openBinding(writer, "xs:element[@name='" + name + "']", indent);
        writeLine(writer, indent + 1, "<jaxb:property name=\"" + escapeXml(names.propertyName(name)) + "\"/>");

        Element inlineComplexType = findDirectChild(element, "complexType");
        if (inlineComplexType != null) {
            writeInlineComplexType(writer, names, name, inlineComplexType, indent + 1);
        }

        closeBinding(writer, indent);
    }

    private void writeGlobalComplexTypeBinding(
            StringBuilder writer,
            BindingNameResolver names,
            Element complexType,
            String typeName,
            int indent
    ) {
        openBinding(writer, "xs:complexType[@name='" + typeName + "']", indent);
        writeLine(writer, indent + 1, "<jaxb:class name=\"" + escapeXml(names.classNameForComplexType(typeName)) + "\"/>");
        writeContentModel(writer, names, complexType, indent, "");
        closeBinding(writer, indent);
    }

    private void writeGlobalSimpleTypeBinding(
            StringBuilder writer,
            BindingNameResolver names,
            Element simpleType,
            String typeName,
            int indent
    ) {
        Map<String, String> enumMembers = vocabulary.enumMembers(typeName);
        if (enumMembers.isEmpty()) {
            enumMembers = readInlineEnumMembers(simpleType);
        }
        if (enumMembers.isEmpty()) {
            return;
        }

        openBinding(writer, "xs:simpleType[@name='" + typeName + "']", indent);
        writeEnumClass(writer, names, typeName, typeName, enumMembers, indent + 1);
        closeBinding(writer, indent);
    }

    private void writeGlobalGroupBinding(
            StringBuilder writer,
            BindingNameResolver names,
            Element group,
            String groupName,
            int indent
    ) {
        openBinding(writer, "xs:group[@name='" + groupName + "']", indent);
        writeContentModel(writer, names, group, indent, "");
        closeBinding(writer, indent);
    }

    private void writeContentModel(
            StringBuilder writer,
            BindingNameResolver names,
            Element container,
            int indent,
            String pathPrefix
    ) {
        for (Element child : xsChildren(container)) {
            String localName = child.getLocalName();
            if ("sequence".equals(localName) || "choice".equals(localName) || "all".equals(localName)) {
                String nextPrefix = pathPrefix.isEmpty()
                        ? "xs:" + localName
                        : pathPrefix + "/xs:" + localName;
                writeContentModel(writer, names, child, indent, nextPrefix);
            } else if ("complexContent".equals(localName)) {
                Element extension = findDirectChild(child, "extension");
                if (extension != null) {
                    String nextPrefix = pathPrefix.isEmpty()
                            ? "xs:complexContent/xs:extension"
                            : pathPrefix + "/xs:complexContent/xs:extension";
                    writeContentModel(writer, names, extension, indent, nextPrefix);
                }
            } else if ("element".equals(localName)) {
                writeElementBinding(writer, names, child, indent, pathPrefix);
            }
        }
    }

    private void writeElementBinding(
            StringBuilder writer,
            BindingNameResolver names,
            Element element,
            int indent,
            String pathPrefix
    ) {
        String ref = element.getAttribute("ref");
        String displayName;
        String node;
        if (!ref.isBlank()) {
            displayName = ref.contains(":") ? ref.substring(ref.indexOf(':') + 1) : ref;
            node = buildElementNode(pathPrefix, "xs:element[@ref='" + ref + "']");
        } else {
            String name = element.getAttribute("name");
            if (name.isBlank()) {
                return;
            }
            displayName = name;
            node = buildElementNode(pathPrefix, "xs:element[@name='" + name + "']");
        }

        openBinding(writer, node, indent);
        writeLine(writer, indent + 1, "<jaxb:property name=\"" + escapeXml(names.propertyName(displayName)) + "\"/>");

        Element inlineComplexType = findDirectChild(element, "complexType");
        Element inlineSimpleType = findDirectChild(element, "simpleType");
        if (inlineComplexType != null) {
            writeInlineComplexType(writer, names, displayName, inlineComplexType, indent + 1);
        } else if (inlineSimpleType != null) {
            Map<String, String> enumMembers = readInlineEnumMembers(inlineSimpleType);
            if (!enumMembers.isEmpty()) {
                openBinding(writer, "xs:simpleType", indent + 1);
                writeEnumClass(writer, names, null, displayName, enumMembers, indent + 2);
                closeBinding(writer, indent + 1);
            }
        } else {
            String typeAttribute = element.getAttribute("type");
            if (!typeAttribute.isBlank()) {
                XmlQName typeName = XmlQName.parse(typeAttribute, element);
                Map<String, String> enumMembers = vocabulary.enumMembers(typeName.localName());
                if (!enumMembers.isEmpty()) {
                    writeEnumClass(writer, names, typeName.localName(), displayName, enumMembers, indent + 1);
                }
            }
        }

        closeBinding(writer, indent);
    }

    private void writeInlineComplexType(
            StringBuilder writer,
            BindingNameResolver names,
            String elementName,
            Element inlineComplexType,
            int indent
    ) {
        openBinding(writer, "xs:complexType", indent);
        writeLine(writer, indent + 1, "<jaxb:class name=\"" + escapeXml(names.classNameForElement(elementName)) + "\"/>");
        writeContentModel(writer, names, inlineComplexType, indent, "");
        closeBinding(writer, indent);
    }

    private void writeEnumClass(
            StringBuilder writer,
            BindingNameResolver names,
            String simpleTypeName,
            String fallbackBase,
            Map<String, String> enumMembers,
            int indent
    ) {
        writeLine(writer, indent, "<jaxb:typesafeEnumClass name=\""
                + escapeXml(names.enumClassName(simpleTypeName, fallbackBase)) + "\">");
        for (Map.Entry<String, String> member : enumMembers.entrySet()) {
            writeLine(writer, indent + 1, "<jaxb:typesafeEnumMember value=\""
                    + escapeXml(member.getKey()) + "\" name=\"" + escapeXml(member.getValue()) + "\"/>");
        }
        writeLine(writer, indent, "</jaxb:typesafeEnumClass>");
    }

    private static String buildElementNode(String pathPrefix, String elementSelector) {
        if (pathPrefix == null || pathPrefix.isBlank()) {
            return elementSelector;
        }
        return pathPrefix + "/" + elementSelector;
    }

    private static Map<String, String> readInlineEnumMembers(Element simpleType) {
        Element restriction = findDirectChild(simpleType, "restriction");
        if (restriction == null) {
            return Map.of();
        }

        Map<String, String> members = new LinkedHashMap<>();
        for (Element child : xsChildren(restriction)) {
            if ("enumeration".equals(child.getLocalName())) {
                String value = child.getAttribute("value");
                if (!value.isBlank()) {
                    members.put(value, BindingNameResolver.toPascalCase("VALUE_" + value).toUpperCase(Locale.ROOT));
                }
            }
        }
        return members;
    }

    private static boolean isDocumentSchema(String schemaId, Element schema) {
        if (schemaId.contains("/docs/") || "demo".equals(schemaId)) {
            return true;
        }
        Element root = findFirstGlobalElement(schema);
        return root != null && findDirectChild(root, "complexType") != null;
    }

    private static Element findFirstGlobalElement(Element schema) {
        for (Element child : xsChildren(schema)) {
            if ("element".equals(child.getLocalName()) && child.hasAttribute("name")) {
                return child;
            }
        }
        return null;
    }

    private static String derivePackageName(String schemaId, String fileName) {
        String baseName = fileName.replaceAll("(?i)\\.xsd$", "");
        Matcher matcher = DATE_SUFFIX.matcher(baseName);
        if (schemaId.contains("/docs/") || schemaId.equals("demo")) {
            if (matcher.matches()) {
                return PACKAGE_BASE + ".docs." + matcher.group(1) + "_"
                        + matcher.group(3) + matcher.group(4) + matcher.group(5);
            }
            return PACKAGE_BASE + ".docs." + baseName.toLowerCase(Locale.ROOT);
        }

        if (matcher.matches()) {
            return PACKAGE_BASE + "." + matcher.group(1) + "._" + matcher.group(3) + "_" + matcher.group(4) + "_" + matcher.group(5);
        }
        return PACKAGE_BASE + "." + baseName.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String deriveSchemaLocation(String schemaId, String fileName) {
        if (schemaId.contains("/docs/")) {
            String baseName = schemaId.substring(schemaId.lastIndexOf('/') + 1);
            return "../../xsd/docs/" + baseName + ".xsd";
        }
        if (schemaId.startsWith("xsd/")) {
            return "../" + schemaId + ".xsd";
        }
        return "../xsd/" + fileName;
    }

    private Document parseDocument(SchemaSource source) {
        try (InputStream inputStream = source.openStream(resourceLoader)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(inputStream));
        } catch (Exception exception) {
            throw new XjbGenerationException("Не удалось разобрать XSD: " + exception.getMessage());
        }
    }

    private static void openBinding(StringBuilder writer, String node, int indent) {
        writeLine(writer, indent, "<jaxb:bindings node=\"" + node + "\">");
    }

    private static void closeBinding(StringBuilder writer, int indent) {
        writeLine(writer, indent, "</jaxb:bindings>");
    }

    private static void writeLine(StringBuilder writer, int indent, String line) {
        writer.append("    ".repeat(Math.max(0, indent))).append(line).append('\n');
    }

    private static List<Element> xsChildren(Element parent) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element && XmlQName.isXs(element)) {
                children.add(element);
            }
        }
        return children;
    }

    private static Element findDirectChild(Element parent, String localName) {
        for (Element child : xsChildren(parent)) {
            if (localName.equals(child.getLocalName())) {
                return child;
            }
        }
        return null;
    }

    private static String escapeXml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    public record GeneratedXjb(
            String schemaId,
            String packageName,
            String rootElement,
            String xjb,
            int vocabularyHits,
            int fallbackHits,
            List<String> unknownNames,
            List<XjbFallbackNameDto> fallbackNames
    ) {
    }
}
