package com.github.botaggregation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.botaggregation.entity.PostTemplate;
import com.github.botaggregation.entity.ProcessedPost;
import com.github.botaggregation.repository.PostTemplateRepository;
import com.github.botaggregation.repository.ProcessedPostRepository;
import com.github.botaggregation.util.EntityToHtmlConverter;
import it.tdlight.jni.TdApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class MessageProcessingService {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{[^}]+}}");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    private final ProcessedPostRepository processedPostRepository;
    private final PostTemplateRepository postTemplateRepository;
    private final OpenAiExtractorService openAiExtractorService;
    private final UrlCleanerService urlCleanerService;
    private final TelegramPublisherService telegramPublisherService;
    private final ObjectMapper objectMapper;

    public void processAlbum(List<TdApi.Message> albumMessages, TdLibClientService tdLibClient) {
        var firstMessage = albumMessages.get(0);
        long chatId = firstMessage.chatId;

        // Step 1: Deduplication — skip if any message in the album was already processed
        for (var msg : albumMessages) {
            if (processedPostRepository.existsBySourceChatIdAndSourceMessageId(chatId, msg.id)) {
                log.debug("[PROCESSING] Album already processed (msg {}/{}), skipping", chatId, msg.id);
                return;
            }
        }

        // Step 2: Extract formatted text from the first message that has a caption
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

        // Step 3: Download images from ALL album messages
        List<File> imageFiles = new ArrayList<>();
        for (var msg : albumMessages) {
            imageFiles.addAll(downloadImages(msg, tdLibClient));
        }

        // Step 4: Template+result must be configured, otherwise block posting
        Optional<PostTemplate> templateOpt = postTemplateRepository.findCurrent();
        if (templateOpt.isEmpty() || !hasTemplateAndResult(templateOpt.get())) {
            log.warn("[PROCESSING] Template and result not configured, blocking publish for album in chat {}", chatId);
            return;
        }

        String html = buildHtml(formattedText, templateOpt.get());
        if (html == null || html.isBlank()) {
            log.info("[PROCESSING] Build HTML returned empty for album in chat {}, skipping", chatId);
            return;
        }

        telegramPublisherService.publishHtml(html, imageFiles);

        // Step 5: Mark all album messages as processed
        for (var msg : albumMessages) {
            var processedPost = new ProcessedPost();
            processedPost.setSourceChatId(chatId);
            processedPost.setSourceMessageId(msg.id);
            processedPost.setProcessedAt(LocalDateTime.now());
            processedPostRepository.save(processedPost);
        }

        log.info("[PROCESSING] Successfully processed album ({} photos) from chat {}",
                imageFiles.size(), chatId);
    }

    public void process(TdApi.Message message, TdLibClientService tdLibClient) {
        long chatId = message.chatId;
        long messageId = message.id;

        // Step 1: Deduplication
        if (processedPostRepository.existsBySourceChatIdAndSourceMessageId(chatId, messageId)) {
            log.debug("[PROCESSING] Duplicate message {}/{}, skipping", chatId, messageId);
            return;
        }

        // Step 2: Extract formatted text
        TdApi.FormattedText formattedText = extractFormattedText(message);
        if (formattedText == null || formattedText.text == null || formattedText.text.isBlank()) {
            log.debug("[PROCESSING] Empty text in message {}/{}, skipping", chatId, messageId);
            return;
        }

        log.info("[PROCESSING] Processing message {}/{}", chatId, messageId);

        // Step 3: Download images
        List<File> imageFiles = downloadImages(message, tdLibClient);

        // Step 4: Template+result must be configured, otherwise block posting
        Optional<PostTemplate> templateOpt = postTemplateRepository.findCurrent();
        if (templateOpt.isEmpty() || !hasTemplateAndResult(templateOpt.get())) {
            log.warn("[PROCESSING] Template and result not configured, blocking publish for message {}/{}", chatId, messageId);
            return;
        }

        String html = buildHtml(formattedText, templateOpt.get());
        if (html == null || html.isBlank()) {
            log.info("[PROCESSING] Build HTML returned empty for message {}/{}, skipping", chatId, messageId);
            return;
        }

        telegramPublisherService.publishHtml(html, imageFiles);

        // Step 5: Save processed post
        var processedPost = new ProcessedPost();
        processedPost.setSourceChatId(chatId);
        processedPost.setSourceMessageId(messageId);
        processedPost.setProcessedAt(LocalDateTime.now());
        processedPostRepository.save(processedPost);

        log.info("[PROCESSING] Successfully processed message {}/{}", chatId, messageId);
    }

    private String buildHtml(TdApi.FormattedText formattedText, PostTemplate template) {
        try {
            String messageText = formattedText.text;

            // Read stored row mapping: label → template line number
            Map<String, Integer> fieldLines = objectMapper.readValue(
                    template.getFieldNames(), new TypeReference<LinkedHashMap<String, Integer>>() {});

            // Read example values for URL params and fallback
            Map<String, String> examples = Map.of();
            if (template.getFieldExamples() != null && !template.getFieldExamples().isBlank()) {
                examples = objectMapper.readValue(
                        template.getFieldExamples(), new TypeReference<Map<String, String>>() {});
            }

            // Build compact field description: "product_name (line 1), price (line 3)"
            var sb = new StringBuilder();
            for (var entry : fieldLines.entrySet()) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(entry.getKey()).append(" (line ").append(entry.getValue()).append(")");
            }

            // Step 2: Send post + field list to AI (short call — no template text)
            Map<String, String> extracted = openAiExtractorService.extractFromPost(
                    messageText, sb.toString());
            if (extracted.isEmpty()) {
                log.warn("[PROCESSING] Post extraction returned empty — structure may not match");
                return null;
            }

            // Step 3: Substitute into result HTML
            String html = template.getResultHtml();
            for (var entry : extracted.entrySet()) {
                String placeholder = "{{" + entry.getKey() + "}}";
                String value = entry.getValue();
                if (value == null || value.isBlank()) continue;

                if (URL_PATTERN.matcher(value).matches()) {
                    // URL value — clean, append template params, substitute
                    String cleaned = urlCleanerService.clean(value);
                    String exampleUrl = examples.get(entry.getKey());
                    if (exampleUrl != null && URL_PATTERN.matcher(exampleUrl).matches()) {
                        cleaned = appendTemplateParams(cleaned, exampleUrl);
                    }
                    String hrefCheck = "href=\"" + placeholder + "\"";
                    if (html.contains(hrefCheck)) {
                        html = html.replace(hrefCheck, "href=\"" + cleaned + "\"");
                    }
                    html = html.replace(placeholder, EntityToHtmlConverter.escapeHtml(cleaned));
                } else {
                    // Text value — preserve formatting from source post
                    String htmlValue = toHtmlWithFormatting(formattedText, value);
                    html = html.replace(placeholder, htmlValue);
                }
            }

            // Fallback: fill remaining placeholders with example values
            if (!examples.isEmpty()) {
                for (var entry : examples.entrySet()) {
                    String placeholder = "{{" + entry.getKey() + "}}";
                    if (html.contains(placeholder) && entry.getValue() != null) {
                        String hrefCheck = "href=\"" + placeholder + "\"";
                        if (html.contains(hrefCheck)) {
                            html = html.replace(hrefCheck, "href=\"" + entry.getValue() + "\"");
                        }
                        html = html.replace(placeholder, EntityToHtmlConverter.escapeHtml(entry.getValue()));
                    }
                }
            }

            html = PLACEHOLDER_PATTERN.matcher(html).replaceAll("");

            return html;
        } catch (Exception e) {
            log.error("[PROCESSING] Failed to build HTML: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Finds the plain-text value in the source post and converts it to HTML
     * with any formatting entities (bold, italic, links, etc.) preserved.
     */
    private String toHtmlWithFormatting(TdApi.FormattedText formattedText, String plainValue) {
        if (formattedText.entities != null && formattedText.entities.length > 0) {
            int idx = formattedText.text.indexOf(plainValue);
            if (idx >= 0) {
                return EntityToHtmlConverter.convertTdLibRange(
                        formattedText.text, formattedText.entities, idx, idx + plainValue.length());
            }
        }
        return EntityToHtmlConverter.escapeHtml(plainValue);
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

    /**
     * Extracts query parameters from the template example URL and appends them
     * to the cleaned source URL, so user's custom params (e.g. ?tag=test) are preserved.
     */
    private String appendTemplateParams(String cleanedUrl, String exampleUrl) {
        try {
            URI exampleUri = URI.create(exampleUrl.trim());
            String exampleQuery = exampleUri.getRawQuery();
            if (exampleQuery == null || exampleQuery.isEmpty()) {
                return cleanedUrl;
            }

            URI cleanedUri = URI.create(cleanedUrl.trim());
            String cleanedQuery = cleanedUri.getRawQuery();

            // Collect existing param keys from cleaned URL to avoid duplicates
            Set<String> existingKeys = new HashSet<>();
            if (cleanedQuery != null) {
                for (String param : cleanedQuery.split("&")) {
                    existingKeys.add(param.split("=", 2)[0].toLowerCase());
                }
            }

            // Append template params that are not already present
            StringBuilder extra = new StringBuilder();
            for (String param : exampleQuery.split("&")) {
                String key = param.split("=", 2)[0].toLowerCase();
                if (!existingKeys.contains(key)) {
                    if (!extra.isEmpty()) extra.append("&");
                    extra.append(param);
                }
            }

            if (extra.isEmpty()) return cleanedUrl;

            return cleanedUrl + (cleanedQuery != null ? "&" : "?") + extra;
        } catch (Exception e) {
            log.warn("[PROCESSING] Failed to append template params: {}", e.getMessage());
            return cleanedUrl;
        }
    }

    private boolean hasTemplateAndResult(PostTemplate template) {
        return template.getResultHtml() != null && !template.getResultHtml().isBlank()
                && template.getFieldNames() != null && !template.getFieldNames().isBlank();
    }

    private List<File> downloadImages(TdApi.Message message, TdLibClientService tdLibClient) {
        List<File> files = new ArrayList<>();
        var content = message.content;

        if (content instanceof TdApi.MessagePhoto photoContent) {
            // Get the largest photo size
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
