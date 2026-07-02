package com.xsdvalidator.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class XmlFormatterTest {

  private final XmlFormatter formatter = new XmlFormatter();

  @Test
  void formatIsIdempotent() {
    String xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <root xmlns="http://example.com"><child><value>test</value></child></root>
        """;

    String once = formatter.format(xml.getBytes(StandardCharsets.UTF_8));
    String twice = formatter.format(once.getBytes(StandardCharsets.UTF_8));

    assertEquals(once, twice);
    assertFalse(once.contains("\n\n"), "formatted XML must not contain blank lines");
  }

  @Test
  void formatPreservesCyrillicNamespaces() {
    String xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <ЭДСФР xmlns="http://пф.рф/ВВ/МСК/МСКД/2024-01-01"><МСКД><Поле>ТЕСТ</Поле></МСКД></ЭДСФР>
        """;

    String formatted = formatter.format(xml.getBytes(StandardCharsets.UTF_8));

    assertFalse(formatted.contains("\n\n"));
    org.junit.jupiter.api.Assertions.assertTrue(formatted.contains("http://пф.рф/ВВ/МСК/МСКД/2024-01-01"));
    org.junit.jupiter.api.Assertions.assertTrue(formatted.contains("<Поле>"));
  }
}
