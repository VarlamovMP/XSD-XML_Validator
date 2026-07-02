package com.xsdvalidator.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(XmlValidatorProperties.class)
public class AppConfig {
}
