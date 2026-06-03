package com.github.botaggregation.service;

import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class BotMessageConverter {

    private static final Set<String> FORMATTING_TYPES = Set.of(
            "bold", "italic", "underline", "strikethrough",
            "code", "pre", "spoiler", "text_link"
    );

    private BotMessageConverter() {}

    /**
     * Converts a Bot API Message (text + entities) to Telegram-compatible HTML.
     * Only processes formatting entities; auto-detected entities (hashtag, url,
     * mention, etc.) are ignored to avoid text duplication.
     */
    public static String toHtml(Message message) {
        String text = message.getText() != null ? message.getText()
                : message.getCaption() != null ? message.getCaption()
                : "";

        List<MessageEntity> entities = message.getEntities() != null ? message.getEntities()
                : message.getCaptionEntities() != null ? message.getCaptionEntities()
                : List.of();

        // Filter to formatting entities only
        List<MessageEntity> formatting = entities.stream()
                .filter(e -> FORMATTING_TYPES.contains(e.getType()))
                .sorted(Comparator.comparingInt(MessageEntity::getOffset)
                        .thenComparing(Comparator.comparingInt(MessageEntity::getLength).reversed()))
                .toList();

        if (formatting.isEmpty()) {
            return escapeHtml(text);
        }

        // Build open/close tag events at each position
        TreeMap<Integer, List<String>> opens = new TreeMap<>();
        TreeMap<Integer, List<String>> closes = new TreeMap<>();

        for (var entity : formatting) {
            int start = entity.getOffset();
            int end = start + entity.getLength();

            opens.computeIfAbsent(start, k -> new ArrayList<>()).add(openTag(entity));
            // Prepend close tags so inner entities close before outer ones
            closes.computeIfAbsent(end, k -> new ArrayList<>()).add(0, closeTag(entity));
        }

        // Merge all event positions
        TreeSet<Integer> positions = new TreeSet<>();
        positions.addAll(opens.keySet());
        positions.addAll(closes.keySet());

        StringBuilder sb = new StringBuilder();
        int pos = 0;

        for (int tagPos : positions) {
            // Append text before this position
            if (tagPos > pos) {
                sb.append(escapeHtml(text.substring(pos, tagPos)));
            }
            // Close tags first
            var closesHere = closes.get(tagPos);
            if (closesHere != null) {
                for (var tag : closesHere) {
                    sb.append(tag);
                }
            }
            // Then open tags
            var opensHere = opens.get(tagPos);
            if (opensHere != null) {
                for (var tag : opensHere) {
                    sb.append(tag);
                }
            }
            pos = tagPos;
        }

        // Append remaining text
        if (pos < text.length()) {
            sb.append(escapeHtml(text.substring(pos)));
        }

        return sb.toString();
    }

    private static String openTag(MessageEntity entity) {
        return switch (entity.getType()) {
            case "bold" -> "<b>";
            case "italic" -> "<i>";
            case "underline" -> "<u>";
            case "strikethrough" -> "<s>";
            case "code" -> "<code>";
            case "pre" -> "<pre>";
            case "spoiler" -> "<tg-spoiler>";
            case "text_link" -> "<a href=\"" + entity.getUrl() + "\">";
            default -> "";
        };
    }

    private static String closeTag(MessageEntity entity) {
        return switch (entity.getType()) {
            case "bold" -> "</b>";
            case "italic" -> "</i>";
            case "underline" -> "</u>";
            case "strikethrough" -> "</s>";
            case "code" -> "</code>";
            case "pre" -> "</pre>";
            case "spoiler" -> "</tg-spoiler>";
            case "text_link" -> "</a>";
            default -> "";
        };
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
