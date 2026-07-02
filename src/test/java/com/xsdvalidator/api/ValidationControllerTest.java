package com.xsdvalidator.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ValidationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listSchemasContainsDemo() throws Exception {
        mockMvc.perform(get("/api/schemas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='demo')]").exists());
    }

    @Test
    void validateValidXmlReturnsTrue() throws Exception {
        byte[] xml = new ClassPathResource("valid-sample.xml").getContentAsByteArray();

        mockMvc.perform(post("/api/validate/demo")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(xml))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.schemaId").value("demo"));
    }

    @Test
    void validateInvalidXmlReturnsErrorsWithLocation() throws Exception {
        byte[] xml = new ClassPathResource("invalid-required.xml").getContentAsByteArray();

        mockMvc.perform(post("/api/validate/demo")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(xml))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].line").value(greaterThan(0)))
                .andExpect(jsonPath("$.errors[0].message").exists());
    }

    @Test
    void unknownSchemaReturns404() throws Exception {
        mockMvc.perform(post("/api/validate/unknown-schema")
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<root/>"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("SCHEMA_NOT_FOUND"));
    }

    @Test
    void uploadSchemaRegistersAndListsIt() throws Exception {
        byte[] xsd = new ClassPathResource("schemas/demo.xsd").getContentAsByteArray();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "uploaded-demo.xsd",
                "application/xml",
                xsd
        );

        mockMvc.perform(multipart("/api/schemas").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered[0].id").value("uploaded-demo"))
                .andExpect(jsonPath("$.registered[0].fileName").value("uploaded-demo.xsd"));

        mockMvc.perform(get("/api/schemas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='uploaded-demo')]").exists());
    }

    @Test
    void uploadDuplicateSchemaReturnsConflict() throws Exception {
        byte[] xsd = new ClassPathResource("schemas/demo.xsd").getContentAsByteArray();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "duplicate.xsd",
                "application/xml",
                xsd
        );

        mockMvc.perform(multipart("/api/schemas").file(file))
                .andExpect(status().isOk());

        mockMvc.perform(multipart("/api/schemas").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_XSD"))
                .andExpect(jsonPath("$.message").value("duplicate.xsd: схема «duplicate» уже существует"));
    }

    @Test
    void validateSchemaIdWithSlashesUsesQueryParam() throws Exception {
        mockMvc.perform(post("/api/validate")
                        .param("schemaId", "xsd/docs/loan_information_request_2024-01-01")
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<root/>"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.schemaId").value("xsd/docs/loan_information_request_2024-01-01"));
    }

    @Test
    void generateTemplateForDemoSchema() throws Exception {
        mockMvc.perform(get("/api/schemas/template").param("schemaId", "demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaId").value("demo"))
                .andExpect(jsonPath("$.rootElement").value("order"))
                .andExpect(jsonPath("$.xml", containsString("<order")))
                .andExpect(jsonPath("$.xml", containsString("<name>NAME</name>")));
    }

    @Test
    void generateTemplateForLoanSchema() throws Exception {
        mockMvc.perform(get("/api/schemas/template")
                        .param("schemaId", "xsd/docs/loan_information_request_2024-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rootElement").value("ЭДСФР"))
                .andExpect(jsonPath("$.xml", containsString("<ЭДСФР")))
                .andExpect(jsonPath("$.xml", containsString(">ИМЯ</")));
    }

    @Test
    void generateTemplateForTypeOnlySchemaReturnsFirstGlobalElement() throws Exception {
        mockMvc.perform(get("/api/schemas/template")
                        .param("schemaId", "xsd/uni_types_2023-04-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rootElement").value("Наименование"))
                .andExpect(jsonPath("$.xml", containsString(">НАИМЕНОВАНИЕ</Наименование>")));
    }

    @Test
    void formatXmlWithCyrillicNamespaces() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ЭДСФР xmlns="http://пф.рф/ВВ/МСК/МСКД/2024-01-01"><МСКД><Поле>ТЕСТ</Поле></МСКД></ЭДСФР>
                """;

        mockMvc.perform(post("/api/xml/format")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(xml.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("http://пф.рф/ВВ/МСК/МСКД/2024-01-01")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("<Поле>")));
    }

    @Test
    void uploadSchemaBundleWithDependency() throws Exception {
        byte[] uniTypes = new ClassPathResource("schemas/demo.xsd").getContentAsByteArray();
        MockMultipartFile dependency = new MockMultipartFile(
                "file",
                "bundle-dep.xsd",
                "application/xml",
                uniTypes
        );
        MockMultipartFile main = new MockMultipartFile(
                "file",
                "bundle-main.xsd",
                "application/xml",
                uniTypes
        );

        mockMvc.perform(multipart("/api/schemas").file(dependency).file(main))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered.length()").value(2))
                .andExpect(jsonPath("$.registered[0].id").value("bundle-dep"))
                .andExpect(jsonPath("$.registered[1].id").value("bundle-main"));
    }
}
