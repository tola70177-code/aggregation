package com.github.botaggregation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tdlib")
@Getter
@Setter
public class TdLibProperties {

    private int apiId;
    private String apiHash;
    private String databaseDirectory = "./tdlib-data";
    private String filesDirectory = "./tdlib-files";
    private String systemLanguage = "en";
    private String deviceModel = "Server";
    private String applicationVersion = "1.0.0";
}
