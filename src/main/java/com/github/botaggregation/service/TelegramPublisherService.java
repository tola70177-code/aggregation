package com.github.botaggregation.service;

import com.github.botaggregation.dto.ExtractedProduct;
import com.github.botaggregation.repository.DestinationChannelRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class TelegramPublisherService {

    private final TelegramClient telegramClient;
    private final DestinationChannelRepository destinationChannelRepository;

    public TelegramPublisherService(TelegramClient telegramClient,
                                    DestinationChannelRepository destinationChannelRepository) {
        this.telegramClient = telegramClient;
        this.destinationChannelRepository = destinationChannelRepository;
    }

    public void publishHtml(String html, List<File> imageFiles) {
        var destination = destinationChannelRepository.findCurrent();
        if (destination.isEmpty()) {
            log.warn("[PUBLISHER] No destination channel configured, skipping publish");
            return;
        }

        String chatId = String.valueOf(destination.get().getChannelId());

        try {
            if (imageFiles == null || imageFiles.isEmpty()) {
                sendTextMessage(chatId, html);
            } else {
                // Try to send image(s) with caption first
                try {
                    if (imageFiles.size() == 1) {
                        sendSinglePhoto(chatId, html, imageFiles.get(0));
                    } else {
                        sendMediaGroup(chatId, html, imageFiles);
                    }
                } catch (Exception captionError) {
                    // Caption too long — fall back to image + separate text message
                    log.debug("[PUBLISHER] Caption too long, splitting into image + text: {}", captionError.getMessage());
                    if (imageFiles.size() == 1) {
                        sendSinglePhoto(chatId, null, imageFiles.get(0));
                    } else {
                        sendMediaGroup(chatId, null, imageFiles);
                    }
                    sendTextMessage(chatId, html);
                }
            }
            log.info("[PUBLISHER] Published HTML message");
        } catch (Exception e) {
            log.error("[PUBLISHER] Failed to publish HTML: {}", e.getMessage(), e);
        }
    }

    public void publish(ExtractedProduct product, List<File> imageFiles) {
        var destination = destinationChannelRepository.findCurrent();
        if (destination.isEmpty()) {
            log.warn("[PUBLISHER] No destination channel configured, skipping publish");
            return;
        }

        String chatId = String.valueOf(destination.get().getChannelId());
        String caption = formatCaption(product);

        try {
            if (imageFiles == null || imageFiles.isEmpty()) {
                sendTextMessage(chatId, caption);
            } else if (imageFiles.size() == 1) {
                sendSinglePhoto(chatId, caption, imageFiles.get(0));
            } else {
                sendMediaGroup(chatId, caption, imageFiles);
            }
            log.info("[PUBLISHER] Published: {}", product.getTitle());
        } catch (Exception e) {
            log.error("[PUBLISHER] Failed to publish: {}", e.getMessage(), e);
        }
    }

    private void sendTextMessage(String chatId, String text) throws Exception {
        var message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("HTML")
                .disableWebPagePreview(true)
                .build();
        telegramClient.execute(message);
    }

    private void sendSinglePhoto(String chatId, String caption, File photo) throws Exception {
        var message = SendPhoto.builder()
                .chatId(chatId)
                .photo(new InputFile(photo))
                .caption(caption)
                .parseMode("HTML")
                .build();
        telegramClient.execute(message);
    }

    private void sendMediaGroup(String chatId, String caption, List<File> photos) throws Exception {
        List<InputMediaPhoto> media = new ArrayList<>();
        for (int i = 0; i < photos.size(); i++) {
            var builder = InputMediaPhoto.builder()
                    .media(photos.get(i), "photo_" + i + ".jpg");
            if (i == 0) {
                builder.caption(caption).parseMode("HTML");
            }
            media.add(builder.build());
        }

        var group = SendMediaGroup.builder()
                .chatId(chatId)
                .medias(new ArrayList<>(media))
                .build();
        telegramClient.execute(group);
    }

    private String formatCaption(ExtractedProduct product) {
        var sb = new StringBuilder();

        if (product.getTitle() != null && !product.getTitle().isBlank()) {
            sb.append(product.getTitle());
        }

        if (product.getPrice() != null && !product.getPrice().isBlank()) {
            sb.append("\n\n\uD83D\uDCB0 Price: ").append(product.getPrice());
        }

        if (product.getUrl() != null && !product.getUrl().isBlank()) {
            sb.append("\n\n\uD83D\uDD17 ").append(product.getUrl());
        }

        return sb.toString();
    }

}
