package com.github.botaggregation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.botaggregation.config.OpenAiProperties;
import com.github.botaggregation.dto.ExtractedProduct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class OpenAiExtractorService {

    private final RestTemplate restTemplate;
    private final OpenAiProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * One-time setup: analyze template + user instruction.
     * Returns JSON with two keys:
     *   "fields" → {label: templateLineNumber}
     *   "output_template" → HTML string with {{label}} placeholders
     */
    public JsonNode analyzeInstruction(String templateText, String instruction) {
        try {
            String numbered = numberLines(templateText);

            String systemPrompt = "You are given a numbered template post and a user instruction.\n"
                    + "The instruction says what to keep from the template and what to change.\n\n"
                    + "Return a JSON object with exactly 2 keys:\n"
                    + "1. \"fields\" — object where keys are snake_case labels and values are the template line numbers containing that data.\n"
                    + "2. \"output_template\" — the desired output as a string "
                    + "with {{label}} placeholders matching the field names.\n\n"
                    + "IMPORTANT: Do NOT wrap {{placeholder}} tokens in any formatting tags "
                    + "like <b>, <i>, <code>, <u>, <s>, etc. "
                    + "The source post's formatting is preserved automatically. "
                    + "Only use HTML tags (<b>, <i>, <a href=\"\">) for STATIC text that you add yourself "
                    + "(labels, emoji descriptions, link text like 'Buy here').\n"
                    + "For URLs use: <a href=\\\"{{url_field}}\\\">link text</a>\n\n"
                    + "Example response:\n"
                    + "{\"fields\":{\"product_name\":1,\"price\":2,\"url\":3},"
                    + "\"output_template\":\"{{product_name}}\\n💰 {{price}}\\n🔗 <a href=\\\"{{url}}\\\">Buy</a>\"}\n\n"
                    + "Return ONLY valid JSON. Do NOT wrap in array.";

            String userMessage = "=== TEMPLATE ===\n" + numbered
                    + "\n\n=== INSTRUCTION ===\n" + instruction;

            String content = callOpenAi(systemPrompt, userMessage);

            JsonNode parsed = objectMapper.readTree(content);
            if (parsed.isArray() && !parsed.isEmpty()) {
                parsed = parsed.get(0);
            }
            return parsed;
        } catch (Exception e) {
            log.error("[OPENAI] Instruction analysis failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Per-message: extract fields from post. Short prompt — just field names + line numbers.
     * fieldLines = "product_name (line 1), price (line 3), url (line 5)"
     */
    public Map<String, String> extractFromPost(String postText, String fieldLines) {
        try {
            String systemPrompt = "Extract from post: " + fieldLines + ". "
                    + "Return flat JSON. Capture full content of each field.";

            String content = callOpenAi(systemPrompt, postText);

            JsonNode parsed = objectMapper.readTree(content);
            if (parsed.isArray() && !parsed.isEmpty()) {
                parsed = parsed.get(0);
            }

            return objectMapper.convertValue(parsed,
                    new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception e) {
            log.error("[OPENAI] Post extraction failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private String callOpenAi(String systemPrompt, String userMessage) throws Exception {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getApiKey());

        var body = Map.of(
                "model", properties.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)
                ),
                "temperature", 0.1
        );

        String jsonBody = objectMapper.writeValueAsString(body);
        var request = new HttpEntity<>(jsonBody, headers);
        String response = restTemplate.postForObject(properties.getApiUrl(), request, String.class);

        JsonNode root = objectMapper.readTree(response);
        String content = root.path("choices").path(0).path("message").path("content").asText().trim();

        if (content.startsWith("```")) {
            content = content.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        }

        return content;
    }

    private String numberLines(String text) {
        String[] lines = text.split("\n", -1);
        var sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("\n");
            sb.append(i + 1).append(": ").append(lines[i]);
        }
        return sb.toString();
    }

    // --- legacy ---

    public ExtractedProduct extract(String messageText) {
        try {
            String content = callOpenAi(
                    "Extract product info. Return JSON: {\"title\":\"\",\"price\":\"\",\"url\":\"\"}",
                    messageText);
            return objectMapper.readValue(content, ExtractedProduct.class);
        } catch (Exception e) {
            log.error("[OPENAI] Extraction failed: {}", e.getMessage());
            return new ExtractedProduct();
        }
    }
}
