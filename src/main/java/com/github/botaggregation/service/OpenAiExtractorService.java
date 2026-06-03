package com.github.botaggregation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.botaggregation.config.OpenAiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class OpenAiExtractorService {

    private final RestTemplate restTemplate;
    private final OpenAiProperties properties;
    private final ObjectMapper objectMapper;

    public record TemplateAnalysis(List<String> fields, Map<String, String> examples) {}

    /**
     * Analyzes a user-provided template to identify field names and their example values.
     * Returns field names and their corresponding values found in the template text,
     * so the caller can do the replacement on the original HTML.
     *
     * @param templateText the user's template text (may contain HTML)
     * @return TemplateAnalysis with field names and example values, or null on error
     */
    public TemplateAnalysis analyzeTemplate(String templateText) {
        try {
            String systemPrompt = "You are a template analyzer.\n"
                    + "The user sends a sample post. Your task is to identify all data fields "
                    + "(e.g. product name, price, link, discount, etc.) and extract their exact values "
                    + "as they appear in the text.\n\n"
                    + "CRITICAL RULES:\n"
                    + "- Assign each field a short snake_case name\n"
                    + "- Extract the COMPLETE value for each field exactly as it appears in the text\n"
                    + "- The product/item name is ALWAYS a single field — never split it\n"
                    + "- Prices with currency symbols are single fields (e.g. '302 ₴ / $6.81' is one value)\n"
                    + "- Ignore any HTML tags when identifying values, but return the plain text value "
                    + "WITHOUT HTML tags\n"
                    + "- URLs are single fields\n"
                    + "- IMPORTANT: Telegram links (t.me/*, telegram.me/*) are NOT data fields. "
                    + "They are static parts of the template and must be completely ignored. "
                    + "Do NOT include them in fields or examples.\n\n"
                    + "Return JSON with exactly these fields:\n"
                    + "- fields: array of field name strings\n"
                    + "- examples: object mapping each field name to its exact value from the text\n\n"
                    + "Return ONLY the JSON object. No comments, no explanation.";

            String userMessage = "POST:\n" + templateText;

            String content = callOpenAi(systemPrompt, userMessage);
            JsonNode json = objectMapper.readTree(content);

            List<String> fields = objectMapper.convertValue(
                    json.path("fields"), new TypeReference<List<String>>() {});
            Map<String, String> examples = objectMapper.convertValue(
                    json.path("examples"), new TypeReference<Map<String, String>>() {});

            if (fields == null || fields.isEmpty() || examples == null || examples.isEmpty()) {
                log.warn("[OPENAI] analyzeTemplate returned empty fields or examples");
                return null;
            }

            return new TemplateAnalysis(fields, examples);
        } catch (Exception e) {
            log.error("[OPENAI] analyzeTemplate failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extracts field values from a post text given a list of required field names.
     *
     * @param postText   the post text (plain text with URLs)
     * @param fieldNames the list of field names to extract
     * @return map of field name to extracted value, or null on error
     */
    public Map<String, String> extractFields(String postText, List<String> fieldNames) {
        try {
            String systemPrompt = "You are a data extractor for Telegram posts.\n"
                    + "Extract the following fields from the post text.\n"
                    + "Return a JSON object with exactly these keys: " + String.join(", ", fieldNames) + "\n"
                    + "Set the value to null if a field is not found in the post.\n"
                    + "CRITICAL: Extract values EXACTLY as they appear in the post text. "
                    + "Preserve ALL characters including currency symbols (₴, $, €, £, ¥), "
                    + "special characters, emoji, units, and formatting. "
                    + "Do NOT strip, modify, or clean any characters from the extracted values. "
                    + "For example, '302 ₴ / $6.81' must be returned as '302 ₴ / $6.81', not '302 / 6.81'.\n"
                    + "IMPORTANT: All URLs must be returned clean, without any tracking or affiliate parameters. "
                    + "Remove these query parameters: utm_source, utm_medium, utm_campaign, utm_term, utm_content, "
                    + "ref, tag, fbclid, gclid, sclid, dclid, msclkid, mc_cid, mc_eid, yclid, _ga, _gl, "
                    + "affiliate_id, aff_id, partner, click_id. "
                    + "If removing parameters leaves an empty query string, remove the '?' as well.\n"
                    + "Return ONLY the JSON object. No comments, no explanation.";

            String userMessage = "POST:\n" + postText;

            String content = callOpenAi(systemPrompt, userMessage);
            Map<String, String> result = objectMapper.readValue(content, new TypeReference<>() {});

            return result;
        } catch (Exception e) {
            log.error("[OPENAI] extractFields failed: {}", e.getMessage());
            return null;
        }
    }

    String callOpenAi(String systemPrompt, String userMessage) throws Exception {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getApiKey());

        var body = java.util.Map.of(
                "model", properties.getModel(),
                "messages", List.of(
                        java.util.Map.of("role", "system", "content", systemPrompt),
                        java.util.Map.of("role", "user", "content", userMessage)
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
}
