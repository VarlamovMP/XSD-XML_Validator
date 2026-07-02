package com.xsdvalidator.core;

import org.springframework.core.io.Resource;
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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class XsdTemplateGenerator {

    private static final String XS_NS = "http://www.w3.org/2001/XMLSchema";
    private static final String XSD_NS_PREFIX = "xs";

    private final SchemaRegistry schemaRegistry;
    private final ResourceLoader resourceLoader;

    public XsdTemplateGenerator(SchemaRegistry schemaRegistry, ResourceLoader resourceLoader) {
        this.schemaRegistry = schemaRegistry;
        this.resourceLoader = resourceLoader;
    }

    public GeneratedTemplate generate(String schemaId) {
        SchemaSource source = schemaRegistry.getSchemaSource(schemaId);
        SchemaModel model = SchemaModel.load(source, schemaRegistry, resourceLoader);
        Element rootDefinition = model.findDocumentRootElement();
        if (rootDefinition == null) {
            throw new TemplateGenerationException(
                    "Схема не содержит корневого элемента документа. Выберите XSD формата документа, а не файл типов."
            );
        }

        XmlWriter writer = new XmlWriter(model);
        String rootName = rootDefinition.getAttribute("name");
        if (rootName.isBlank()) {
            throw new TemplateGenerationException("Корневой элемент XSD не имеет имени");
        }

        writer.writeDeclaration();
        writer.writeElement(rootDefinition, rootName, elementNamespace(rootDefinition, model), new HashSet<>());
        return new GeneratedTemplate(schemaId, rootName, writer.build());
    }

    public record GeneratedTemplate(String schemaId, String rootElement, String xml) {
    }

    private static final class SchemaModel {

        private final SchemaRegistry schemaRegistry;
        private final Map<String, Document> documents = new LinkedHashMap<>();
        private final Map<XmlQName, Element> globalElements = new LinkedHashMap<>();
        private final Map<XmlQName, Element> complexTypes = new LinkedHashMap<>();
        private final Map<XmlQName, Element> simpleTypes = new LinkedHashMap<>();
        private final Map<XmlQName, Element> groups = new LinkedHashMap<>();
        private final Map<String, String> targetNamespaceByUri = new LinkedHashMap<>();
        private final Document mainDocument;

        private SchemaModel(SchemaRegistry schemaRegistry, Document mainDocument) {
            this.schemaRegistry = schemaRegistry;
            this.mainDocument = mainDocument;
        }

        static SchemaModel load(SchemaSource source, SchemaRegistry registry, ResourceLoader resourceLoader) {
            try {
                Document mainDocument = parse(source, resourceLoader);
                SchemaModel model = new SchemaModel(registry, mainDocument);
                model.loadRecursive(mainDocument.getDocumentElement(), source.baseUri(resourceLoader));
                return model;
            } catch (IOException exception) {
                throw new TemplateGenerationException("Не удалось прочитать XSD: " + exception.getMessage());
            }
        }

        private static Document parse(SchemaSource source, ResourceLoader resourceLoader) throws IOException {
            try (InputStream inputStream = source.openStream(resourceLoader)) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                DocumentBuilder builder = factory.newDocumentBuilder();
                return builder.parse(new InputSource(inputStream));
            } catch (Exception exception) {
                throw new TemplateGenerationException("Не удалось разобрать XSD: " + exception.getMessage());
            }
        }

        private void loadRecursive(Element schemaElement, String baseUri) throws IOException {
            String systemId = baseUri != null ? baseUri : "main.xsd";
            if (documents.containsKey(systemId)) {
                return;
            }

            Document document = schemaElement.getOwnerDocument();
            documents.put(systemId, document);
            indexSchema(schemaElement);

            for (Element child : childElements(schemaElement)) {
                if (!XmlQName.isXs(child)) {
                    continue;
                }
                String localName = child.getLocalName();
                if ("import".equals(localName) || "include".equals(localName)) {
                    loadDependency(child.getAttribute("schemaLocation"), systemId);
                }
            }
        }

        private void loadDependency(String schemaLocation, String baseUri) throws IOException {
            if (schemaLocation == null || schemaLocation.isBlank()) {
                return;
            }

            String resolvedUri = resolveUri(schemaLocation, baseUri);
            if (documents.containsKey(resolvedUri)) {
                return;
            }

            try (InputStream inputStream = openDependency(schemaLocation, baseUri)) {
                if (inputStream == null) {
                    return;
                }
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document document = builder.parse(new InputSource(inputStream));
                Element schemaElement = document.getDocumentElement();
                loadRecursive(schemaElement, resolvedUri);
            } catch (Exception exception) {
                throw new TemplateGenerationException(
                        "Не удалось загрузить зависимость XSD «" + schemaLocation + "»: " + exception.getMessage()
                );
            }
        }

        private InputStream openDependency(String schemaLocation, String baseUri) throws IOException {
            InMemoryResourceResolver memory = schemaRegistry.uploadedResourceResolver();
            var memoryInput = memory.resolveResource(
                    null,
                    null,
                    null,
                    schemaLocation,
                    baseUri
            );
            if (memoryInput != null && memoryInput.getByteStream() != null) {
                return memoryInput.getByteStream();
            }

            ClasspathResourceResolver classpath = schemaRegistry.classpathResourceResolver();
            var classpathInput = classpath.resolveResource(
                    null,
                    null,
                    null,
                    schemaLocation,
                    baseUri
            );
            if (classpathInput != null && classpathInput.getByteStream() != null) {
                return classpathInput.getByteStream();
            }
            return null;
        }

        private static String resolveUri(String schemaLocation, String baseUri) {
            if (baseUri == null || baseUri.isBlank()) {
                return schemaLocation;
            }
            try {
                return java.net.URI.create(baseUri).resolve(schemaLocation).toString();
            } catch (IllegalArgumentException exception) {
                return schemaLocation;
            }
        }

        private void indexSchema(Element schemaElement) {
            String targetNamespace = schemaElement.getAttribute("targetNamespace");
            if (!targetNamespace.isBlank()) {
                targetNamespaceByUri.put(targetNamespace, targetNamespace);
            }

            for (Element child : childElements(schemaElement)) {
                if (!XmlQName.isXs(child)) {
                    continue;
                }
                String name = child.getAttribute("name");
                if (name.isBlank()) {
                    continue;
                }
                XmlQName qName = new XmlQName(targetNamespace, name);
                switch (child.getLocalName()) {
                    case "element" -> globalElements.putIfAbsent(qName, child);
                    case "complexType" -> complexTypes.putIfAbsent(qName, child);
                    case "simpleType" -> simpleTypes.putIfAbsent(qName, child);
                    case "group" -> groups.putIfAbsent(qName, child);
                    default -> {
                    }
                }
            }
        }

        Element findDocumentRootElement() {
            Element schema = mainDocument.getDocumentElement();
            for (Element child : childElements(schema)) {
                if (XmlQName.isXs(child) && "element".equals(child.getLocalName()) && child.hasAttribute("name")) {
                    return child;
                }
            }
            return null;
        }

        Element findGlobalElement(XmlQName qName) {
            Element element = globalElements.get(qName);
            if (element != null) {
                return element;
            }
            throw new TemplateGenerationException("Не найден глобальный элемент «" + qName.localName() + "»");
        }

        Element findComplexType(XmlQName qName) {
            Element type = complexTypes.get(qName);
            if (type != null) {
                return type;
            }
            throw new TemplateGenerationException("Не найден тип «" + qName.localName() + "»");
        }

        boolean isSimpleType(XmlQName qName) {
            return simpleTypes.containsKey(qName);
        }

        Element findGroup(XmlQName qName) {
            Element group = groups.get(qName);
            if (group != null) {
                return group;
            }
            throw new TemplateGenerationException("Не найдена группа «" + qName.localName() + "»");
        }

        String elementFormDefault(Element schemaElement) {
            String value = schemaElement.getAttribute("elementFormDefault");
            return value.isBlank() ? "unqualified" : value;
        }
    }

    private static String elementNamespace(Element elementDefinition, SchemaModel model) {
        Element schema = XmlQName.schemaElement(elementDefinition);
        if (schema == null) {
            return "";
        }
        if (elementDefinition.hasAttribute("ref")) {
            XmlQName ref = XmlQName.parse(elementDefinition.getAttribute("ref"), elementDefinition);
            Element global = model.findGlobalElement(ref);
            schema = XmlQName.schemaElement(global);
            return schema.getAttribute("targetNamespace");
        }
        if ("qualified".equals(model.elementFormDefault(schema))) {
            return schema.getAttribute("targetNamespace");
        }
        return "";
    }

    private static final class XmlWriter {

        private final StringBuilder xml = new StringBuilder();
        private final SchemaModel model;
        private final NamespaceRegistry namespaces = new NamespaceRegistry();
        private int depth;

        private XmlWriter(SchemaModel model) {
            this.model = model;
        }

        private void writeDeclaration() {
            xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        }

        private void writeElement(Element definition, String localName, String namespace, Set<XmlQName> visitingTypes) {
            if (definition.hasAttribute("ref")) {
                XmlQName ref = XmlQName.parse(definition.getAttribute("ref"), definition);
                Element global = model.findGlobalElement(ref);
                writeResolvedElement(global, ref.localName(), ref.namespace(), visitingTypes);
                return;
            }

            writeResolvedElement(definition, localName, namespace, visitingTypes);
        }

        private void writeResolvedElement(Element definition, String localName, String namespace, Set<XmlQName> visitingTypes) {
            Element inlineComplexType = firstChildElement(definition, "complexType");
            Element inlineSimpleType = firstChildElement(definition, "simpleType");
            String typeName = definition.getAttribute("type");

            if (inlineComplexType != null || (typeName.isBlank() && inlineSimpleType == null && hasParticleChildren(definition))) {
                writeComplexElement(localName, namespace, definition, visitingTypes);
                return;
            }

            if (!typeName.isBlank()) {
                XmlQName typeQName = XmlQName.parse(typeName, definition);
                if (isBuiltInType(typeQName) || model.isSimpleType(typeQName)) {
                    writeLeaf(localName, namespace, placeholder(localName));
                    return;
                }
                Element complexType = model.findComplexType(typeQName);
                writeComplexTypeContent(localName, namespace, complexType, visitingTypes);
                return;
            }

            if (inlineSimpleType != null || hasSimpleContent(definition)) {
                writeLeaf(localName, namespace, placeholder(localName));
                return;
            }

            writeLeaf(localName, namespace, placeholder(localName));
        }

        private void writeComplexElement(
                String localName,
                String namespace,
                Element definition,
                Set<XmlQName> visitingTypes
        ) {
            Element inlineComplexType = firstChildElement(definition, "complexType");
            if (inlineComplexType != null) {
                writeComplexTypeContent(localName, namespace, inlineComplexType, visitingTypes);
                return;
            }

            writeStartTag(localName, namespace, collectAttributes(definition, null));
            writeParticles(definition, namespace, visitingTypes);
            writeEndTag(localName, namespace);
        }

        private void writeComplexTypeContent(
                String localName,
                String namespace,
                Element complexType,
                Set<XmlQName> visitingTypes
        ) {
            XmlQName typeQName = namedTypeQName(complexType);
            if (typeQName != null) {
                if (!visitingTypes.add(typeQName)) {
                    writeLeaf(localName, namespace, placeholder(localName));
                    return;
                }
            }

            List<AttributeSpec> attributes = collectAttributes(complexType, null);
            Element content = firstContentDeclaration(complexType);
            if (content == null) {
                writeStartTag(localName, namespace, attributes);
                writeTypeBody(complexType, namespace, visitingTypes);
                writeEndTag(localName, namespace);
                if (typeQName != null) {
                    visitingTypes.remove(typeQName);
                }
                return;
            }

            String contentLocalName = content.getLocalName();
            if ("simpleContent".equals(contentLocalName)) {
                writeStartTag(localName, namespace, mergeAttributes(attributes, collectAttributes(content, null)));
                xml.append(escapeXml(placeholder(localName)));
                writeEndTag(localName, namespace);
                if (typeQName != null) {
                    visitingTypes.remove(typeQName);
                }
                return;
            }

            if ("complexContent".equals(contentLocalName)) {
                Element extension = firstChildElement(content, "extension", "restriction");
                if (extension != null && extension.hasAttribute("base")) {
                    XmlQName baseType = XmlQName.parse(extension.getAttribute("base"), extension);
                    if (!isBuiltInType(baseType)) {
                        try {
                            Element baseComplexType = model.findComplexType(baseType);
                            List<AttributeSpec> baseAttributes = collectAttributes(baseComplexType, null);
                            attributes = mergeAttributes(baseAttributes, attributes);
                            writeStartTag(localName, namespace, mergeAttributes(attributes, collectAttributes(extension, null)));
                            writeTypeBody(baseComplexType, namespace, visitingTypes);
                            writeParticles(extension, namespace, visitingTypes);
                            writeEndTag(localName, namespace);
                            if (typeQName != null) {
                                visitingTypes.remove(typeQName);
                            }
                            return;
                        } catch (TemplateGenerationException ignored) {
                            // fall through to extension particles only
                        }
                    }
                }
                writeStartTag(localName, namespace, mergeAttributes(attributes, collectAttributes(content, null)));
                if (extension != null) {
                    writeParticles(extension, namespace, visitingTypes);
                }
                writeEndTag(localName, namespace);
                if (typeQName != null) {
                    visitingTypes.remove(typeQName);
                }
                return;
            }

            writeStartTag(localName, namespace, attributes);
            writeTypeBody(complexType, namespace, visitingTypes);
            writeEndTag(localName, namespace);
            if (typeQName != null) {
                visitingTypes.remove(typeQName);
            }
        }

        private void writeTypeBody(Element complexType, String namespace, Set<XmlQName> visitingTypes) {
            Element sequence = firstChildElement(complexType, "sequence", "choice", "all", "group");
            if (sequence != null) {
                writeParticle(sequence, namespace, visitingTypes);
            }
        }

        private void writeParticles(Element container, String namespace, Set<XmlQName> visitingTypes) {
            for (Element child : childElements(container)) {
                if (!XmlQName.isXs(child)) {
                    continue;
                }
                String localName = child.getLocalName();
                if ("sequence".equals(localName) || "choice".equals(localName) || "all".equals(localName) || "group".equals(localName) || "element".equals(localName)) {
                    writeParticle(child, namespace, visitingTypes);
                }
            }
        }

        private void writeParticle(Element particle, String parentNamespace, Set<XmlQName> visitingTypes) {
            String particleName = particle.getLocalName();
            if ("element".equals(particleName)) {
                if (particle.hasAttribute("ref")) {
                    XmlQName ref = XmlQName.parse(particle.getAttribute("ref"), particle);
                    Element global = model.findGlobalElement(ref);
                    writeElement(global, ref.localName(), ref.namespace(), visitingTypes);
                    return;
                }
                String name = particle.getAttribute("name");
                writeElement(particle, name, elementNamespace(particle, model), visitingTypes);
                return;
            }

            if ("group".equals(particleName)) {
                if (particle.hasAttribute("ref")) {
                    XmlQName ref = XmlQName.parse(particle.getAttribute("ref"), particle);
                    Element group = model.findGroup(ref);
                    writeParticleContent(group, parentNamespace, visitingTypes);
                    return;
                }
                writeParticleContent(particle, parentNamespace, visitingTypes);
                return;
            }

            if ("choice".equals(particleName)) {
                Element first = firstParticleChild(particle);
                if (first != null) {
                    writeParticle(first, parentNamespace, visitingTypes);
                }
                return;
            }

            for (Element child : childElements(particle)) {
                if (XmlQName.isXs(child) && isParticle(child.getLocalName())) {
                    writeParticle(child, parentNamespace, visitingTypes);
                }
            }
        }

        private void writeParticleContent(Element groupOrParticle, String parentNamespace, Set<XmlQName> visitingTypes) {
            Element child = firstParticleChild(groupOrParticle);
            if (child == null) {
                return;
            }
            if ("choice".equals(child.getLocalName())) {
                Element first = firstParticleChild(child);
                if (first != null) {
                    writeParticle(first, parentNamespace, visitingTypes);
                }
                return;
            }
            writeParticle(child, parentNamespace, visitingTypes);
        }

        private void writeLeaf(String localName, String namespace, String text) {
            writeIndent();
            if (depth == 0 && !namespace.isBlank()) {
                namespaces.setDefaultNamespace(namespace);
                xml.append('<').append(localName)
                        .append(" xmlns=\"").append(escapeXml(namespace)).append('"')
                        .append('>').append(escapeXml(text)).append("</").append(localName).append(">\n");
                return;
            }
            xml.append('<').append(namespaces.qualify(localName, namespace));
            namespaces.writePendingDeclarations(xml);
            xml.append('>').append(escapeXml(text)).append("</")
                    .append(namespaces.qualify(localName, namespace)).append(">\n");
        }

        private void writeStartTag(String localName, String namespace, List<AttributeSpec> attributes) {
            writeIndent();
            if (depth == 0 && !namespace.isBlank()) {
                namespaces.setDefaultNamespace(namespace);
                xml.append('<').append(localName)
                        .append(" xmlns=\"").append(escapeXml(namespace)).append('"');
            } else {
                xml.append('<').append(namespaces.qualify(localName, namespace));
                namespaces.registerFromAttributes(attributes);
                namespaces.writePendingDeclarations(xml);
            }
            if (depth == 0) {
                namespaces.registerFromAttributes(attributes);
            }
            for (AttributeSpec attribute : attributes) {
                xml.append(' ')
                        .append(namespaces.qualify(attribute.localName(), attribute.namespace()))
                        .append("=\"")
                        .append(escapeXml(attribute.value()))
                        .append('"');
            }
            xml.append(">\n");
            depth++;
        }

        private void writeEndTag(String localName, String namespace) {
            depth--;
            writeIndent();
            if (depth == 0 && namespace.equals(namespaces.defaultNamespace())) {
                xml.append("</").append(localName).append(">\n");
                return;
            }
            xml.append("</").append(namespaces.qualify(localName, namespace)).append(">\n");
        }

        private void writeIndent() {
            xml.append("    ".repeat(Math.max(0, depth)));
        }

        private String build() {
            return xml.toString();
        }
    }

    private static final class NamespaceRegistry {

        private final Map<String, String> prefixByNamespace = new LinkedHashMap<>();
        private final Map<String, String> namespaceByPrefix = new LinkedHashMap<>();
        private final Set<String> pendingNamespaces = new LinkedHashSet<>();
        private String defaultNamespace;
        private int generatedPrefixCounter;

        private void registerFromAttributes(List<AttributeSpec> attributes) {
            for (AttributeSpec attribute : attributes) {
                if (!attribute.namespace().isBlank()) {
                    ensurePrefix(attribute.namespace());
                }
            }
        }

        private void setDefaultNamespace(String namespace) {
            this.defaultNamespace = namespace;
        }

        private String defaultNamespace() {
            return defaultNamespace != null ? defaultNamespace : "";
        }

        private String qualify(String localName, String namespace) {
            if (namespace == null || namespace.isBlank() || namespace.equals(defaultNamespace)) {
                return localName;
            }
            String prefix = ensurePrefix(namespace);
            pendingNamespaces.add(namespace);
            return prefix + ":" + localName;
        }

        private String ensurePrefix(String namespace) {
            String existing = prefixByNamespace.get(namespace);
            if (existing != null) {
                return existing;
            }

            String preferred = preferredPrefix(namespace);
            if (preferred != null && !namespaceByPrefix.containsKey(preferred)) {
                prefixByNamespace.put(namespace, preferred);
                namespaceByPrefix.put(preferred, namespace);
                return preferred;
            }

            String generated;
            do {
                generated = "ns" + (++generatedPrefixCounter);
            } while (namespaceByPrefix.containsKey(generated));

            prefixByNamespace.put(namespace, generated);
            namespaceByPrefix.put(generated, namespace);
            return generated;
        }

        private static String preferredPrefix(String namespace) {
            if (namespace.contains("/УТ/")) {
                return "УТ7";
            }
            if (namespace.contains("/АФ/")) {
                return "АФ7";
            }
            if (namespace.contains("xmldsig")) {
                return "ds";
            }
            return null;
        }

        private void writePendingDeclarations(StringBuilder xml) {
            for (String namespace : pendingNamespaces) {
                String prefix = prefixByNamespace.get(namespace);
                if (prefix != null) {
                    xml.append(" xmlns:").append(prefix).append("=\"").append(escapeXml(namespace)).append('"');
                }
            }
            pendingNamespaces.clear();
        }
    }

    private record AttributeSpec(String namespace, String localName, String value) {
    }

    private static List<AttributeSpec> collectAttributes(Element container, Element additional) {
        List<AttributeSpec> attributes = new ArrayList<>();
        if (container != null) {
            for (Element child : childElements(container)) {
                if (XmlQName.isXs(child) && "attribute".equals(child.getLocalName())) {
                    attributes.add(toAttributeSpec(child));
                }
            }
        }
        if (additional != null) {
            for (Element child : childElements(additional)) {
                if (XmlQName.isXs(child) && "attribute".equals(child.getLocalName())) {
                    attributes.add(toAttributeSpec(child));
                }
            }
        }
        return attributes;
    }

    private static AttributeSpec toAttributeSpec(Element attribute) {
        String name = attribute.getAttribute("name");
        String ref = attribute.getAttribute("ref");
        if (!ref.isBlank()) {
            XmlQName qName = XmlQName.parse(ref, attribute);
            name = qName.localName();
        }
        String namespace = "";
        if (attribute.hasAttribute("form") && "qualified".equals(attribute.getAttribute("form"))) {
            Element schema = XmlQName.schemaElement(attribute);
            namespace = schema != null ? schema.getAttribute("targetNamespace") : "";
        }
        return new AttributeSpec(namespace, name, placeholder(name));
    }

    private static List<AttributeSpec> mergeAttributes(List<AttributeSpec> first, List<AttributeSpec> second) {
        Map<String, AttributeSpec> merged = new LinkedHashMap<>();
        for (AttributeSpec attribute : first) {
            merged.put(attribute.localName(), attribute);
        }
        for (AttributeSpec attribute : second) {
            merged.put(attribute.localName(), attribute);
        }
        return new ArrayList<>(merged.values());
    }

    private static XmlQName namedTypeQName(Element typeElement) {
        if (!typeElement.hasAttribute("name")) {
            return null;
        }
        Element schema = XmlQName.schemaElement(typeElement);
        String namespace = schema != null ? schema.getAttribute("targetNamespace") : "";
        return new XmlQName(namespace, typeElement.getAttribute("name"));
    }

    private static Element firstContentDeclaration(Element complexType) {
        return firstChildElement(complexType, "simpleContent", "complexContent");
    }

    private static Element firstChildElement(Element parent, String... localNames) {
        Set<String> names = Set.of(localNames);
        for (Element child : childElements(parent)) {
            if (XmlQName.isXs(child) && names.contains(child.getLocalName())) {
                return child;
            }
        }
        return null;
    }

    private static Element firstParticleChild(Element parent) {
        for (Element child : childElements(parent)) {
            if (XmlQName.isXs(child) && isParticle(child.getLocalName())) {
                return child;
            }
        }
        return null;
    }

    private static boolean isParticle(String localName) {
        return "element".equals(localName)
                || "sequence".equals(localName)
                || "choice".equals(localName)
                || "all".equals(localName)
                || "group".equals(localName);
    }

    private static boolean hasParticleChildren(Element element) {
        for (Element child : childElements(element)) {
            if (XmlQName.isXs(child) && isParticle(child.getLocalName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSimpleContent(Element element) {
        return firstChildElement(element, "simpleType") != null;
    }

    private static boolean isBuiltInType(XmlQName typeQName) {
        return XS_NS.equals(typeQName.namespace()) || XSD_NS_PREFIX.equals(typeQName.namespace());
    }

    private static List<Element> childElements(Element parent) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element) {
                children.add(element);
            }
        }
        return children;
    }

    private static String placeholder(String name) {
        if (name == null || name.isBlank()) {
            return "ЗНАЧЕНИЕ";
        }
        return name.toUpperCase(Locale.ROOT);
    }

    private static String escapeXml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
