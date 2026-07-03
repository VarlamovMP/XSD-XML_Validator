package com.xsdvalidator.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class BindingNameResolver {

    private static final String CYRILLIC =
            "абвгдеёжзийклмнопрстуфхцчшщъыьэюяАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ";
    private static final String LATIN =
            "abvgdeezzijklmnoprstufkccss_y_euaABVGDEEZZIJKLMNOPRSTUFKCCSS_Y_EUA";

    private final BindingVocabulary vocabulary;
    private final Set<String> unknownNames = new LinkedHashSet<>();
    private final List<FallbackBinding> fallbackBindings = new ArrayList<>();
    private int vocabularyHits;
    private int fallbackHits;

    BindingNameResolver(BindingVocabulary vocabulary) {
        this.vocabulary = vocabulary;
    }

    String propertyName(String xsdName) {
        String known = vocabulary.elementProperty(xsdName);
        if (known != null) {
            vocabularyHits++;
            return known;
        }
        String generated = toCamelCase(transliterate(xsdName));
        registerFallback(xsdName, generated, "property");
        return generated;
    }

    String classNameForElement(String xsdName) {
        String known = vocabulary.elementClass(xsdName);
        if (known != null) {
            vocabularyHits++;
            return known;
        }
        String property = vocabulary.elementProperty(xsdName);
        if (property != null) {
            vocabularyHits++;
            return toPascalCase(property);
        }
        String generated = toPascalCase(transliterate(xsdName));
        registerFallback(xsdName, generated, "class");
        return generated;
    }

    String classNameForComplexType(String xsdName) {
        String known = vocabulary.complexTypeClass(xsdName);
        if (known != null) {
            vocabularyHits++;
            return known;
        }
        String generated = complexTypeFallbackName(xsdName);
        registerFallback(xsdName, generated, "complexType");
        return generated;
    }

    String classNameForSimpleType(String xsdName) {
        String known = vocabulary.enumClass(xsdName);
        if (known != null) {
            vocabularyHits++;
            return known;
        }
        known = vocabulary.simpleTypeClass(xsdName);
        if (known != null) {
            vocabularyHits++;
            return known;
        }
        String generated = simpleTypeFallbackName(xsdName);
        registerFallback(xsdName, generated, "simpleType");
        return generated;
    }

    String classNameForGroup(String xsdName) {
        String known = vocabulary.groupClass(xsdName);
        if (known != null) {
            vocabularyHits++;
            return known;
        }
        String generated = groupFallbackName(xsdName);
        registerFallback(xsdName, generated, "group");
        return generated;
    }

    String enumClassName(String xsdName, String fallbackBase) {
        String known = vocabulary.enumClass(xsdName);
        if (known != null) {
            vocabularyHits++;
            return known;
        }
        if (xsdName != null && !xsdName.isBlank()) {
            String generated = simpleTypeFallbackName(xsdName);
            registerFallback(xsdName, generated, "enum");
            return generated;
        }
        return toPascalCase(fallbackBase);
    }

    List<String> unknownNames() {
        return List.copyOf(unknownNames);
    }

    List<FallbackBinding> fallbackBindings() {
        return List.copyOf(fallbackBindings);
    }

    int vocabularyHits() {
        return vocabularyHits;
    }

    int fallbackHits() {
        return fallbackHits;
    }

    private void registerFallback(String xsdName, String javaName, String bindingKind) {
        fallbackHits++;
        unknownNames.add(xsdName);
        fallbackBindings.add(new FallbackBinding(xsdName, javaName, bindingKind));
    }

    private static String complexTypeFallbackName(String xsdName) {
        if (xsdName.startsWith("Тип")) {
            return toPascalCase(transliterate(xsdName.substring(3))) + "Type";
        }
        return toPascalCase(transliterate(xsdName)) + "Type";
    }

    private static String simpleTypeFallbackName(String xsdName) {
        if (xsdName.startsWith("Тип")) {
            return toPascalCase(transliterate(xsdName.substring(3))) + "Type";
        }
        return toPascalCase(transliterate(xsdName)) + "Type";
    }

    private static String groupFallbackName(String xsdName) {
        if (xsdName.startsWith("Гр")) {
            return toPascalCase(transliterate(xsdName.substring(2)));
        }
        return toPascalCase(transliterate(xsdName));
    }

    static String transliterate(String value) {
        StringBuilder builder = new StringBuilder(value.length() * 2);
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            int cyrIndex = CYRILLIC.indexOf(ch);
            if (cyrIndex >= 0) {
                builder.append(LATIN.charAt(cyrIndex));
            } else if (Character.isLetterOrDigit(ch)) {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    static String toCamelCase(String value) {
        if (value == null || value.isBlank()) {
            return "value";
        }
        String pascal = toPascalCase(value);
        if (pascal.isEmpty()) {
            return "value";
        }
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    static String toPascalCase(String value) {
        if (value == null || value.isBlank()) {
            return "Value";
        }
        List<String> parts = splitWords(value);
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return builder.isEmpty() ? "Value" : builder.toString();
    }

    private static List<String> splitWords(String value) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '_' || ch == '-' || ch == ' ') {
                flushPart(parts, current);
                continue;
            }
            if (!current.isEmpty() && Character.isUpperCase(ch) && Character.isLowerCase(current.charAt(current.length() - 1))) {
                flushPart(parts, current);
            }
            current.append(ch);
        }
        flushPart(parts, current);
        return parts;
    }

    private static void flushPart(List<String> parts, StringBuilder current) {
        if (!current.isEmpty()) {
            parts.add(current.toString());
            current.setLength(0);
        }
    }

    record FallbackBinding(String xsdName, String javaName, String bindingKind) {
    }
}
