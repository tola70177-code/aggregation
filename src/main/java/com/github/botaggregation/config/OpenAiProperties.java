package com.github.botaggregation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openai")
@Getter
@Setter
public class OpenAiProperties {

    private String apiKey;
    private String model = "gpt-4.1";
    private String apiUrl = "https://api.openai.com/v1/chat/completions";
}
