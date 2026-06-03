package com.github.botaggregation.service;

import com.github.botaggregation.entity.DestinationChannel;
import com.github.botaggregation.repository.DestinationChannelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.File;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@ExtendWith(MockitoExtension.class)
class TelegramPublisherServiceTest {

    @Mock private TelegramClient telegramClient;
    @Mock private DestinationChannelRepository destinationChannelRepository;

    private TelegramPublisherService service;

    @BeforeEach
    void setUp() {
        service = new TelegramPublisherService(telegramClient, destinationChannelRepository);
    }

    private DestinationChannel destination(long channelId) {
        var dest = new DestinationChannel();
        dest.setChannelId(channelId);
        return dest;
    }

    // ---- publishHtml ----

    @Test
    void publishHtml_noDestination_returnsFalse() throws Exception {
        when(destinationChannelRepository.findCurrent()).thenReturn(Optional.empty());

        assertThat(service.publishHtml("text", List.of())).isFalse();
        verify(telegramClient, never()).execute(any(SendMessage.class));
    }

    @Test
    void publishHtml_noImages_sendsTextMessage() throws Exception {
        when(destinationChannelRepository.findCurrent()).thenReturn(Optional.of(destination(123L)));

        boolean result = service.publishHtml("<b>Hello</b>", List.of());

        assertThat(result).isTrue();
        var captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).isEqualTo("<b>Hello</b>");
        assertThat(captor.getValue().getParseMode()).isEqualTo("HTML");
    }

    @Test
    void publishHtml_singleImage_sendsSinglePhoto() throws Exception {
        when(destinationChannelRepository.findCurrent()).thenReturn(Optional.of(destination(123L)));

        File tempFile = File.createTempFile("test", ".jpg");
        tempFile.deleteOnExit();

        boolean result = service.publishHtml("Caption", List.of(tempFile));

        assertThat(result).isTrue();
        verify(telegramClient).execute(any(SendPhoto.class));
    }

    @Test
    void publishHtml_totalFailure_returnsFalse() throws Exception {
        when(destinationChannelRepository.findCurrent()).thenReturn(Optional.of(destination(123L)));
        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(new TelegramApiException("Network error"));

        boolean result = service.publishHtml("Text", List.of());

        assertThat(result).isFalse();
    }
}
