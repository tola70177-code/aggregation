package com.github.botaggregation.service;

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

    public boolean publishHtml(String html, List<File> imageFiles) {
        var destination = destinationChannelRepository.findCurrent();
        if (destination.isEmpty()) {
            log.warn("[PUBLISHER] No destination channel configured, skipping publish");
            return false;
        }

        String chatId = String.valueOf(destination.get().getChannelId());

        try {
            if (imageFiles == null || imageFiles.isEmpty()) {
                sendTextMessage(chatId, html);
            } else {
                // Try to send image(s) with caption
                try {
                    if (imageFiles.size() == 1) {
                        sendSinglePhoto(chatId, html, imageFiles.get(0));
                    } else {
                        sendMediaGroup(chatId, html, imageFiles);
                    }
                } catch (Exception captionError) {
                    // Caption too long — send text only, link preview will show an image
                    log.warn("[PUBLISHER] Caption too long, sending text only: {}", captionError.getMessage());
                    sendTextMessage(chatId, html);
                }
            }
            log.info("[PUBLISHER] Published HTML message");
            return true;
        } catch (Exception e) {
            log.error("[PUBLISHER] Failed to publish HTML: {}", e.getMessage(), e);
            return false;
        }
    }

    private void sendTextMessage(String chatId, String text) throws Exception {
        var message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("HTML")
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

}
