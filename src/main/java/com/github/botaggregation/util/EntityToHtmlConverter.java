package com.github.botaggregation.util;

import it.tdlight.jni.TdApi;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

public class EntityToHtmlConverter {

    private EntityToHtmlConverter() {
    }

    public static String convert(String text, List<MessageEntity> entities) {
        if (text == null || text.isEmpty()) return "";
        if (entities == null || entities.isEmpty()) return escapeHtml(text);

        List<MessageEntity> sorted = new ArrayList<>(entities);
        sorted.sort(Comparator.comparingInt(MessageEntity::getOffset)
                .thenComparing(Comparator.comparingInt(MessageEntity::getLength).reversed()));

        TreeMap<Integer, List<MessageEntity>> opens = new TreeMap<>();
        TreeMap<Integer, List<MessageEntity>> closes = new TreeMap<>();

        for (MessageEntity entity : sorted) {
            int start = entity.getOffset();
            int end = start + entity.getLength();
            opens.computeIfAbsent(start, k -> new ArrayList<>()).add(entity);
            closes.computeIfAbsent(end, k -> new ArrayList<>()).add(entity);
        }

        StringBuilder sb = new StringBuilder();
        int len = text.length();

        for (int i = 0; i <= len; i++) {
            if (closes.containsKey(i)) {
                List<MessageEntity> closingEntities = closes.get(i);
                for (int j = closingEntities.size() - 1; j >= 0; j--) {
                    sb.append(closeTag(closingEntities.get(j)));
                }
            }

            if (opens.containsKey(i)) {
                for (MessageEntity entity : opens.get(i)) {
                    sb.append(openTag(entity));
                }
            }

            if (i < len) {
                sb.append(escapeHtmlChar(text.charAt(i)));
            }
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
            case "text_link" -> "<a href=\"" + entity.getUrl() + "\">";
            case "spoiler" -> "<tg-spoiler>";
            case "blockquote" -> "<blockquote>";
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
            case "text_link" -> "</a>";
            case "spoiler" -> "</tg-spoiler>";
            case "blockquote" -> "</blockquote>";
            default -> "";
        };
    }

    /**
     * Converts a substring range of TDLib FormattedText to HTML,
     * preserving any formatting entities that overlap with [rangeStart, rangeEnd).
     */
    public static String convertTdLibRange(String text, TdApi.TextEntity[] entities,
                                            int rangeStart, int rangeEnd) {
        if (text == null || rangeStart < 0 || rangeEnd > text.length() || rangeStart >= rangeEnd) {
            return "";
        }
        if (entities == null || entities.length == 0) {
            return escapeHtml(text.substring(rangeStart, rangeEnd));
        }

        List<TdApi.TextEntity> relevant = new ArrayList<>();
        for (TdApi.TextEntity entity : entities) {
            int eStart = entity.offset;
            int eEnd = entity.offset + entity.length;
            if (eStart < rangeEnd && eEnd > rangeStart) {
                relevant.add(entity);
            }
        }

        if (relevant.isEmpty()) {
            return escapeHtml(text.substring(rangeStart, rangeEnd));
        }

        relevant.sort(Comparator.comparingInt((TdApi.TextEntity e) -> e.offset)
                .thenComparing(Comparator.comparingInt((TdApi.TextEntity e) -> e.length).reversed()));

        TreeMap<Integer, List<TdApi.TextEntity>> opens = new TreeMap<>();
        TreeMap<Integer, List<TdApi.TextEntity>> closes = new TreeMap<>();

        for (TdApi.TextEntity entity : relevant) {
            int openAt = Math.max(entity.offset, rangeStart);
            int closeAt = Math.min(entity.offset + entity.length, rangeEnd);
            opens.computeIfAbsent(openAt, k -> new ArrayList<>()).add(entity);
            closes.computeIfAbsent(closeAt, k -> new ArrayList<>()).add(entity);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = rangeStart; i <= rangeEnd; i++) {
            if (closes.containsKey(i)) {
                List<TdApi.TextEntity> closingEntities = closes.get(i);
                for (int j = closingEntities.size() - 1; j >= 0; j--) {
                    sb.append(closeTagTdLib(closingEntities.get(j)));
                }
            }
            if (opens.containsKey(i)) {
                for (TdApi.TextEntity entity : opens.get(i)) {
                    sb.append(openTagTdLib(entity));
                }
            }
            if (i < rangeEnd) {
                sb.append(escapeHtmlChar(text.charAt(i)));
            }
        }

        return sb.toString();
    }

    private static String openTagTdLib(TdApi.TextEntity entity) {
        var type = entity.type;
        if (type instanceof TdApi.TextEntityTypeBold) return "<b>";
        if (type instanceof TdApi.TextEntityTypeItalic) return "<i>";
        if (type instanceof TdApi.TextEntityTypeUnderline) return "<u>";
        if (type instanceof TdApi.TextEntityTypeStrikethrough) return "<s>";
        if (type instanceof TdApi.TextEntityTypeCode) return "<code>";
        if (type instanceof TdApi.TextEntityTypePre) return "<pre>";
        if (type instanceof TdApi.TextEntityTypePreCode) return "<pre><code>";
        if (type instanceof TdApi.TextEntityTypeTextUrl textUrl) return "<a href=\"" + textUrl.url + "\">";
        if (type instanceof TdApi.TextEntityTypeSpoiler) return "<tg-spoiler>";
        if (type instanceof TdApi.TextEntityTypeBlockQuote) return "<blockquote>";
        return "";
    }

    private static String closeTagTdLib(TdApi.TextEntity entity) {
        var type = entity.type;
        if (type instanceof TdApi.TextEntityTypeBold) return "</b>";
        if (type instanceof TdApi.TextEntityTypeItalic) return "</i>";
        if (type instanceof TdApi.TextEntityTypeUnderline) return "</u>";
        if (type instanceof TdApi.TextEntityTypeStrikethrough) return "</s>";
        if (type instanceof TdApi.TextEntityTypeCode) return "</code>";
        if (type instanceof TdApi.TextEntityTypePre) return "</pre>";
        if (type instanceof TdApi.TextEntityTypePreCode) return "</code></pre>";
        if (type instanceof TdApi.TextEntityTypeTextUrl) return "</a>";
        if (type instanceof TdApi.TextEntityTypeSpoiler) return "</tg-spoiler>";
        if (type instanceof TdApi.TextEntityTypeBlockQuote) return "</blockquote>";
        return "";
    }

    public static String escapeHtml(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            sb.append(escapeHtmlChar(text.charAt(i)));
        }
        return sb.toString();
    }

    private static String escapeHtmlChar(char c) {
        return switch (c) {
            case '&' -> "&amp;";
            case '<' -> "&lt;";
            case '>' -> "&gt;";
            case '"' -> "&quot;";
            default -> String.valueOf(c);
        };
    }
}
