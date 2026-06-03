package com.github.botaggregation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.botaggregation.entity.ProcessedPost;
import com.github.botaggregation.entity.UserTemplate;
import com.github.botaggregation.repository.ProcessedPostRepository;
import com.github.botaggregation.repository.UserTemplateRepository;
import it.tdlight.jni.TdApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class MessageProcessingService {

    private final ProcessedPostRepository processedPostRepository;
    private final UserTemplateRepository userTemplateRepository;
    private final OpenAiExtractorService openAiExtractorService;
    private final TelegramPublisherService telegramPublisherService;
    private final ObjectMapper objectMapper;

    record BuildResult(String content, Map<String, String> fields) {}

    public void processAlbum(List<TdApi.Message> albumMessages, TdLibClientService tdLibClient) {
        var firstMessage = albumMessages.get(0);
        long chatId = firstMessage.chatId;

        // Step 1: Extract text from the first message that has a caption
        TdApi.FormattedText formattedText = null;
        for (var msg : albumMessages) {
            formattedText = extractFormattedText(msg);
            if (formattedText != null && formattedText.text != null && !formattedText.text.isBlank()) break;
        }
        if (formattedText == null || formattedText.text == null || formattedText.text.isBlank()) {
            log.debug("[PROCESSING] No text in album from chat {}, skipping", chatId);
            return;
        }

        log.info("[PROCESSING] Processing album ({} messages) from chat {}", albumMessages.size(), chatId);

        // Step 2: Download images from ALL album messages (only if template expects images)
        List<File> imageFiles = new ArrayList<>();
        if (templateHasImage()) {
            for (var msg : albumMessages) {
                imageFiles.addAll(downloadImages(msg, tdLibClient));
            }
        }

        // Step 3: Build content via field extraction + template substitution
        BuildResult buildResult = buildContent(chatId, formattedText);
        if (buildResult == null || buildResult.content() == null || buildResult.content().isBlank()) {
            log.info("[PROCESSING] Build content returned empty for album in chat {}, skipping", chatId);
            return;
        }

        // Step 3b: Deduplication by link
        String contentLink = buildResult.fields().get("link");
        if (contentLink != null && !contentLink.isBlank() && !"null".equals(contentLink)) {
            if (processedPostRepository.existsByContentLink(contentLink)) {
                log.info("[PROCESSING] Duplicate link detected for album in chat {}: {}, skipping", chatId, contentLink);
                return;
            }
        }

        boolean published = telegramPublisherService.publishHtml(buildResult.content(), imageFiles);
        if (!published) {
            log.warn("[PROCESSING] Publish failed for album in chat {}, will retry next time", chatId);
            return;
        }

        // Step 4: Save processed post
        String contentFieldsJson = serializeFields(buildResult.fields());
        var processedPost = new ProcessedPost();
        processedPost.setSourceChannelId(chatId);
        processedPost.setContentLink(contentLink);
        processedPost.setContentFields(contentFieldsJson);
        processedPostRepository.save(processedPost);

        log.info("[PROCESSING] Successfully processed album ({} photos) from chat {}",
                imageFiles.size(), chatId);
    }

    public void process(TdApi.Message message, TdLibClientService tdLibClient) {
        long chatId = message.chatId;
        long messageId = message.id;

        // Step 1: Extract text
        TdApi.FormattedText formattedText = extractFormattedText(message);
        if (formattedText == null || formattedText.text == null || formattedText.text.isBlank()) {
            log.debug("[PROCESSING] Empty text in message {}/{}, skipping", chatId, messageId);
            return;
        }

        log.info("[PROCESSING] Processing message {}/{}", chatId, messageId);

        // Step 2: Download images (only if template expects images)
        List<File> imageFiles = templateHasImage()
                ? downloadImages(message, tdLibClient)
                : List.of();

        // Step 3: Build content via field extraction + template substitution
        BuildResult buildResult = buildContent(chatId, formattedText);
        if (buildResult == null || buildResult.content() == null || buildResult.content().isBlank()) {
            log.info("[PROCESSING] Build content returned empty for message {}/{}, skipping", chatId, messageId);
            return;
        }

        // Step 3b: Deduplication by link
        String contentLink = buildResult.fields().get("link");
        if (contentLink != null && !contentLink.isBlank() && !"null".equals(contentLink)) {
            if (processedPostRepository.existsByContentLink(contentLink)) {
                log.info("[PROCESSING] Duplicate link detected for message {}/{}: {}, skipping", chatId, messageId, contentLink);
                return;
            }
        }

        boolean published = telegramPublisherService.publishHtml(buildResult.content(), imageFiles);
        if (!published) {
            log.warn("[PROCESSING] Publish failed for message {}/{}, will retry next time", chatId, messageId);
            return;
        }

        // Step 4: Save processed post
        String contentFieldsJson = serializeFields(buildResult.fields());
        var processedPost = new ProcessedPost();
        processedPost.setSourceChannelId(chatId);
        processedPost.setContentLink(contentLink);
        processedPost.setContentFields(contentFieldsJson);
        processedPostRepository.save(processedPost);

        log.info("[PROCESSING] Successfully processed message {}/{}", chatId, messageId);
    }

    BuildResult buildContent(long chatId, TdApi.FormattedText formattedText) {
        // Global template is required
        Optional<UserTemplate> templateOpt = userTemplateRepository.findCurrent();
        if (templateOpt.isEmpty()) {
            log.warn("[PROCESSING] No template configured, skipping chat {}", chatId);
            return null;
        }

        UserTemplate userTemplate = templateOpt.get();

        // Parse field names from fields (JSON array like ["title","price","link"])
        String templateFieldsJson = userTemplate.getFields();
        if (templateFieldsJson == null || templateFieldsJson.isBlank()) {
            log.warn("[PROCESSING] No template fields configured, skipping chat {}", chatId);
            return null;
        }

        List<String> fieldNames;
        try {
            fieldNames = objectMapper.readValue(templateFieldsJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("[PROCESSING] Failed to parse template fields JSON: {}", e.getMessage());
            return null;
        }

        // Get user's template (with {field_name} placeholders)
        String template = userTemplate.getTemplateText();
        if (template == null || template.isBlank()) {
            log.warn("[PROCESSING] No template text configured, skipping chat {}", chatId);
            return null;
        }

        // Extract plain text with URLs from post
        String postText = extractTextWithUrls(formattedText);

        // Call AI to extract field values
        Map<String, String> fields = openAiExtractorService.extractFields(postText, fieldNames);
        if (fields == null) {
            log.info("[PROCESSING] extractFields returned null for chat {}, skipping", chatId);
            return null;
        }

        // Check all field values are non-null and non-empty
        for (String fieldName : fieldNames) {
            String value = fields.get(fieldName);
            if (value == null || value.isBlank() || "null".equals(value)) {
                log.info("[PROCESSING] REJECTED: missing field '{}' for chat {}", fieldName, chatId);
                return null;
            }
        }

        // Clean URLs in field values before substitution (mutable copy in case source map is immutable)
        Map<String, String> cleanedFields = new java.util.HashMap<>(fields);
        for (Map.Entry<String, String> entry : cleanedFields.entrySet()) {
            entry.setValue(cleanUrls(entry.getValue()));
        }

        // Replace {field_name} placeholders in template
        String result = template;
        for (Map.Entry<String, String> entry : cleanedFields.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }

        return new BuildResult(result, cleanedFields);
    }

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    private static final Pattern TRACKING_PARAMS = Pattern.compile(
            "(?i)[?&](?:utm_\\w+|ref|tag|fbclid|gclid|sclid|dclid|msclkid"
                    + "|mc_cid|mc_eid|yclid|_ga|_gl|affiliate_id|aff_id|partner|click_id)=[^&]*");

    /**
     * Cleans all URLs found in the value:
     * - Strips affiliate redirect paths (e.g. s.click.aliexpress.com/e/_XXXXX → s.click.aliexpress.com/e/)
     * - Removes tracking query parameters
     */
    String cleanUrls(String value) {
        if (value == null) return null;
        return URL_PATTERN.matcher(value).replaceAll(match -> cleanSingleUrl(match.group()));
    }

    private String cleanSingleUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return url;

            // Strip affiliate redirect paths
            // s.click.aliexpress.com/e/_XXXXX → s.click.aliexpress.com/e/
            if (host.contains("click.aliexpress.com") && uri.getPath() != null) {
                String path = uri.getPath();
                if (path.startsWith("/e/")) {
                    return uri.getScheme() + "://" + host + "/e/";
                }
            }

            // Strip tracking query parameters
            String cleaned = url;
            cleaned = TRACKING_PARAMS.matcher(cleaned).replaceAll("");
            // Fix leftover '?' if all params were removed
            if (cleaned.endsWith("?")) {
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            }
            // Fix '?&' → '?' when first param was removed
            cleaned = cleaned.replace("?&", "?");

            return cleaned;
        } catch (Exception e) {
            return url;
        }
    }

    private boolean templateHasImage() {
        return userTemplateRepository.findCurrent()
                .map(UserTemplate::isHasImage)
                .orElse(false);
    }

    private String serializeFields(Map<String, String> fields) {
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (Exception e) {
            log.warn("[PROCESSING] Failed to serialize fields: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extracts plain text from FormattedText, appending URLs from TextEntityTypeTextUrl
     * entities that aren't visible in the text body.
     */
    String extractTextWithUrls(TdApi.FormattedText formattedText) {
        StringBuilder sb = new StringBuilder(formattedText.text);

        if (formattedText.entities != null) {
            List<String> hiddenUrls = new ArrayList<>();
            for (var entity : formattedText.entities) {
                if (entity.type instanceof TdApi.TextEntityTypeTextUrl textUrl) {
                    String url = textUrl.url;
                    // Check if this URL is already visible in the text
                    if (!formattedText.text.contains(url)) {
                        hiddenUrls.add(url);
                    }
                }
            }
            if (!hiddenUrls.isEmpty()) {
                sb.append("\n");
                for (String url : hiddenUrls) {
                    sb.append("\n").append(url);
                }
            }
        }

        return sb.toString();
    }

    private TdApi.FormattedText extractFormattedText(TdApi.Message message) {
        var content = message.content;

        if (content instanceof TdApi.MessageText textContent) {
            return textContent.text;
        }
        if (content instanceof TdApi.MessagePhoto photoContent) {
            return photoContent.caption;
        }
        if (content instanceof TdApi.MessageVideo videoContent) {
            return videoContent.caption;
        }
        if (content instanceof TdApi.MessageDocument docContent) {
            return docContent.caption;
        }
        return null;
    }

    private List<File> downloadImages(TdApi.Message message, TdLibClientService tdLibClient) {
        List<File> files = new ArrayList<>();
        var content = message.content;

        if (content instanceof TdApi.MessagePhoto photoContent) {
            var sizes = photoContent.photo.sizes;
            if (sizes != null && sizes.length > 0) {
                var largest = sizes[sizes.length - 1];
                var downloaded = tdLibClient.downloadFileSync(largest.photo.id);
                if (downloaded != null && downloaded.local != null && downloaded.local.isDownloadingCompleted) {
                    files.add(new File(downloaded.local.path));
                }
            }
        }

        return files;
    }
}
