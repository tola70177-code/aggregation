package com.github.botaggregation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.botaggregation.config.OpenAiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpenAiExtractorServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private OpenAiProperties properties;
    private ObjectMapper objectMapper;
    private OpenAiExtractorService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new OpenAiProperties();
        properties.setApiKey("test-key");
        properties.setModel("gpt-4o-mini");
        properties.setApiUrl("https://api.openai.com/v1/chat/completions");
        service = new OpenAiExtractorService(restTemplate, properties, objectMapper);
    }

    private String openAiResponse(String content) {
        return "{\"choices\":[{\"message\":{\"content\":" + objectMapper.valueToTree(content) + "}}]}";
    }

    // ---- analyzeTemplate ----

    @Test
    void analyzeTemplate_returnsFieldsAndExamples() {
        String json = "{\"fields\":[\"title\",\"price\",\"link\"],\"examples\":{\"title\":\"Shoes\",\"price\":\"$50\",\"link\":\"https://shop.com\"}}";
        String response = openAiResponse(json);
        when(restTemplate.postForObject(eq(properties.getApiUrl()), any(HttpEntity.class), eq(String.class)))
                .thenReturn(response);

        var result = service.analyzeTemplate("Shoes\n$50\nhttps://shop.com");

        assertThat(result).isNotNull();
        assertThat(result.fields()).containsExactly("title", "price", "link");
        assertThat(result.examples()).containsEntry("title", "Shoes");
        assertThat(result.examples()).containsEntry("price", "$50");
        assertThat(result.examples()).containsEntry("link", "https://shop.com");
    }

    @Test
    void analyzeTemplate_apiError_returnsNull() {
        when(restTemplate.postForObject(eq(properties.getApiUrl()), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("API error"));

        var result = service.analyzeTemplate("Template text");

        assertThat(result).isNull();
    }

    @Test
    void analyzeTemplate_emptyFields_returnsNull() {
        String json = "{\"fields\":[],\"examples\":{}}";
        String response = openAiResponse(json);
        when(restTemplate.postForObject(eq(properties.getApiUrl()), any(HttpEntity.class), eq(String.class)))
                .thenReturn(response);

        var result = service.analyzeTemplate("Template text");

        assertThat(result).isNull();
    }

    @Test
    void analyzeTemplate_sendsCorrectPrompt() {
        String json = "{\"fields\":[\"title\"],\"examples\":{\"title\":\"my template\"}}";
        String response = openAiResponse(json);
        when(restTemplate.postForObject(eq(properties.getApiUrl()), any(HttpEntity.class), eq(String.class)))
                .thenReturn(response);

        service.analyzeTemplate("my template");

        var captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForObject(eq(properties.getApiUrl()), captor.capture(), eq(String.class));

        String body = (String) captor.getValue().getBody();
        assertThat(body).contains("POST:");
        assertThat(body).contains("my template");
        assertThat(body).contains("template analyzer");
    }

    // ---- extractFields ----

    @Test
    void extractFields_returnsExtractedValues() {
        String json = "{\"title\":\"Nike Shoes\",\"price\":\"$50\",\"link\":\"https://shop.com\"}";
        String response = openAiResponse(json);
        when(restTemplate.postForObject(eq(properties.getApiUrl()), any(HttpEntity.class), eq(String.class)))
                .thenReturn(response);

        var result = service.extractFields("Nike Shoes for $50 at https://shop.com",
                List.of("title", "price", "link"));

        assertThat(result).isNotNull();
        assertThat(result.get("title")).isEqualTo("Nike Shoes");
        assertThat(result.get("price")).isEqualTo("$50");
        assertThat(result.get("link")).isEqualTo("https://shop.com");
    }

    @Test
    void extractFields_apiError_returnsNull() {
        when(restTemplate.postForObject(eq(properties.getApiUrl()), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("API error"));

        var result = service.extractFields("Post text", List.of("title"));

        assertThat(result).isNull();
    }

    @Test
    void extractFields_nullField_returnedInMap() {
        String json = "{\"title\":\"Shoes\",\"price\":null}";
        String response = openAiResponse(json);
        when(restTemplate.postForObject(eq(properties.getApiUrl()), any(HttpEntity.class), eq(String.class)))
                .thenReturn(response);

        var result = service.extractFields("Shoes on sale", List.of("title", "price"));

        assertThat(result).isNotNull();
        assertThat(result.get("title")).isEqualTo("Shoes");
        assertThat(result.get("price")).isNull();
    }

    @Test
    void extractFields_sendsCorrectPrompt() {
        String json = "{\"title\":\"Test\"}";
        String response = openAiResponse(json);
        when(restTemplate.postForObject(eq(properties.getApiUrl()), any(HttpEntity.class), eq(String.class)))
                .thenReturn(response);

        service.extractFields("my post text", List.of("title", "price"));

        var captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForObject(eq(properties.getApiUrl()), captor.capture(), eq(String.class));

        String body = (String) captor.getValue().getBody();
        assertThat(body).contains("POST:");
        assertThat(body).contains("my post text");
        assertThat(body).contains("title, price");
    }

    @Test
    void extractFields_stripsMarkdownCodeBlock() {
        String response = openAiResponse("```json\n{\"title\":\"Test\"}\n```");
        when(restTemplate.postForObject(eq(properties.getApiUrl()), any(HttpEntity.class), eq(String.class)))
                .thenReturn(response);

        var result = service.extractFields("post", List.of("title"));
        assertThat(result).isNotNull();
        assertThat(result.get("title")).isEqualTo("Test");
    }

    @Test
    void extractFields_makesExactlyOneApiCall() {
        String json = "{\"title\":\"Test\"}";
        String response = openAiResponse(json);
        when(restTemplate.postForObject(eq(properties.getApiUrl()), any(HttpEntity.class), eq(String.class)))
                .thenReturn(response);

        service.extractFields("post", List.of("title"));

        verify(restTemplate, times(1)).postForObject(
                eq(properties.getApiUrl()), any(HttpEntity.class), eq(String.class));
    }
}
