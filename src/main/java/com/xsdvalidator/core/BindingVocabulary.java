package com.xsdvalidator.core;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class BindingVocabulary {

    private static final String VOCABULARY_PATH = "bindings/vocabulary.yml";

    private Map<String, String> elements = Map.of();
    private Map<String, String> elementsAsClass = Map.of();
    private Map<String, String> complexTypes = Map.of();
    private Map<String, String> simpleTypes = Map.of();
    private Map<String, String> groups = Map.of();
    private Map<String, String> enumClasses = Map.of();
    private Map<String, Map<String, String>> enumMembers = Map.of();

    @PostConstruct
    void load() throws IOException {
        try (InputStream inputStream = new ClassPathResource(VOCABULARY_PATH).getInputStream()) {
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(inputStream);
            if (!(loaded instanceof Map<?, ?> root)) {
                throw new IllegalStateException("Invalid vocabulary format: " + VOCABULARY_PATH);
            }

            elements = readStringMap(root.get("elements"));
            elementsAsClass = readStringMap(root.get("elementsAsClass"));
            complexTypes = readStringMap(root.get("complexTypes"));
            simpleTypes = readStringMap(root.get("simpleTypes"));
            groups = readStringMap(root.get("groups"));
            enumClasses = readStringMap(root.get("enumClasses"));
            enumMembers = readEnumMembers(root.get("enumMembers"));
        }
    }

    public String elementProperty(String xsdName) {
        return elements.get(xsdName);
    }

    public String elementClass(String xsdName) {
        return elementsAsClass.get(xsdName);
    }

    public String complexTypeClass(String xsdName) {
        return complexTypes.get(xsdName);
    }

    public String simpleTypeClass(String xsdName) {
        return simpleTypes.get(xsdName);
    }

    public String groupClass(String xsdName) {
        return groups.get(xsdName);
    }

    public String enumClass(String xsdName) {
        return enumClasses.get(xsdName);
    }

    public Map<String, String> enumMembers(String xsdName) {
        Map<String, String> members = enumMembers.get(xsdName);
        return members == null ? Map.of() : members;
    }

    public int size() {
        return elements.size() + complexTypes.size() + enumClasses.size();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> readStringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> readEnumMembers(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), readStringMap(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }
}
