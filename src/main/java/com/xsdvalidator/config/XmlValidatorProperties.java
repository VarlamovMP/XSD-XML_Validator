package com.xsdvalidator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "xml-validator")
public class XmlValidatorProperties {

    private String schemasDirectory = "classpath:schemas";
    private long maxXmlSizeBytes = 10 * 1024 * 1024;

    public String getSchemasDirectory() {
        return schemasDirectory;
    }

    public void setSchemasDirectory(String schemasDirectory) {
        this.schemasDirectory = schemasDirectory;
    }

    public long getMaxXmlSizeBytes() {
        return maxXmlSizeBytes;
    }

    public void setMaxXmlSizeBytes(long maxXmlSizeBytes) {
        this.maxXmlSizeBytes = maxXmlSizeBytes;
    }
}
