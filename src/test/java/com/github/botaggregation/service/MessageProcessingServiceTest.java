package com.github.botaggregation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.botaggregation.entity.UserTemplate;
import com.github.botaggregation.repository.ProcessedPostRepository;
import com.github.botaggregation.repository.UserTemplateRepository;
import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageProcessingServiceTest {

    @Mock private ProcessedPostRepository processedPostRepository;
    @Mock private UserTemplateRepository userTemplateRepository;
    @Mock private OpenAiExtractorService openAiExtractorService;
    @Mock private TelegramPublisherService telegramPublisherService;
    @Mock private TdLibClientService tdLibClient;

    private MessageProcessingService service;

    @BeforeEach
    void setUp() {
        service = new MessageProcessingService(
                processedPostRepository,
                userTemplateRepository,
                openAiExtractorService,
                telegramPublisherService,
                new ObjectMapper()
        );
    }

    private TdApi.Message createTextMessage(long chatId, long messageId, String text) {
        var msg = new TdApi.Message();
        msg.chatId = chatId;
        msg.id = messageId;
        var formattedText = new TdApi.FormattedText(text, new TdApi.TextEntity[0]);
        msg.content = new TdApi.MessageText(formattedText, null, null);
        return msg;
    }

    private void setupTemplate(String templateText, String fieldsJson) {
        var template = new UserTemplate();
        template.setTemplateText(templateText);
        template.setFields(fieldsJson);
        when(userTemplateRepository.findCurrent()).thenReturn(Optional.of(template));
    }

    // ---- process() ----

    @Test
    void process_noTemplate_skips() {
        var msg = createTextMessage(100L, 1L, "Hello");
        when(userTemplateRepository.findCurrent()).thenReturn(Optional.empty());

        service.process(msg, tdLibClient);

        verify(openAiExtractorService, never()).extractFields(any(), any());
        verify(telegramPublisherService, never()).publishHtml(any(), any());
    }

    @Test
    void process_withTemplate_extractsFieldsAndPublishes() {
        var msg = createTextMessage(100L, 1L, "Nike Shoes $50 https://shop.com");

        setupTemplate("{title}\n{price}\n{link}", "[\"title\",\"price\",\"link\"]");

        when(openAiExtractorService.extractFields(eq("Nike Shoes $50 https://shop.com"), eq(List.of("title", "price", "link"))))
                .thenReturn(Map.of("title", "Nike Shoes", "price", "$50", "link", "https://shop.com"));
        when(processedPostRepository.existsByContentLink("https://shop.com")).thenReturn(false);
        when(telegramPublisherService.publishHtml(any(), any())).thenReturn(true);

        service.process(msg, tdLibClient);

        verify(telegramPublisherService).publishHtml(eq("Nike Shoes\n$50\nhttps://shop.com"), any());
        verify(processedPostRepository).save(argThat(p ->
                p.getSourceChannelId() == 100L
                        && "https://shop.com".equals(p.getContentLink())
                        && p.getContentFields() != null));
    }

    @Test
    void process_missingField_skips() {
        var msg = createTextMessage(100L, 1L, "Just text no price");

        setupTemplate("{title}\n{price}", "[\"title\",\"price\"]");

        when(openAiExtractorService.extractFields(any(), any()))
                .thenReturn(Map.of("title", "Just text", "price", "null"));

        service.process(msg, tdLibClient);

        verify(telegramPublisherService, never()).publishHtml(any(), any());
        verify(processedPostRepository, never()).save(any());
    }

    @Test
    void process_extractFieldsReturnsNull_skips() {
        var msg = createTextMessage(100L, 1L, "Some text");

        setupTemplate("{title}", "[\"title\"]");

        when(openAiExtractorService.extractFields(any(), any())).thenReturn(null);

        service.process(msg, tdLibClient);

        verify(telegramPublisherService, never()).publishHtml(any(), any());
        verify(processedPostRepository, never()).save(any());
    }

    @Test
    void process_noTemplateFields_skips() {
        var msg = createTextMessage(100L, 1L, "Some text");

        var template = new UserTemplate();
        template.setTemplateText("{title}");
        template.setFields(null);
        when(userTemplateRepository.findCurrent()).thenReturn(Optional.of(template));

        service.process(msg, tdLibClient);

        verify(openAiExtractorService, never()).extractFields(any(), any());
        verify(telegramPublisherService, never()).publishHtml(any(), any());
    }

    @Test
    void process_publishFails_doesNotSaveProcessed() {
        var msg = createTextMessage(100L, 1L, "Post text");

        setupTemplate("{title}", "[\"title\"]");

        when(openAiExtractorService.extractFields(any(), any()))
                .thenReturn(Map.of("title", "Post text"));
        when(telegramPublisherService.publishHtml(any(), any())).thenReturn(false);

        service.process(msg, tdLibClient);

        verify(processedPostRepository, never()).save(any());
    }

    @Test
    void process_emptyText_skips() {
        var msg = createTextMessage(100L, 1L, "   ");

        service.process(msg, tdLibClient);

        verify(userTemplateRepository, never()).findCurrent();
    }

    // ---- link-based dedup ----

    @Test
    void process_duplicateLink_skips() {
        var msg = createTextMessage(100L, 2L, "Nike Shoes $50 https://shop.com");

        setupTemplate("{title}\n{price}\n{link}", "[\"title\",\"price\",\"link\"]");

        when(openAiExtractorService.extractFields(any(), any()))
                .thenReturn(Map.of("title", "Nike Shoes", "price", "$50", "link", "https://shop.com"));
        when(processedPostRepository.existsByContentLink("https://shop.com")).thenReturn(true);

        service.process(msg, tdLibClient);

        verify(telegramPublisherService, never()).publishHtml(any(), any());
        verify(processedPostRepository, never()).save(any());
    }

    @Test
    void process_noLinkField_skipsLinkDedup() {
        var msg = createTextMessage(100L, 1L, "Post without link");

        setupTemplate("{title}\n{price}", "[\"title\",\"price\"]");

        when(openAiExtractorService.extractFields(any(), any()))
                .thenReturn(Map.of("title", "Post", "price", "$10"));
        when(telegramPublisherService.publishHtml(any(), any())).thenReturn(true);

        service.process(msg, tdLibClient);

        verify(telegramPublisherService).publishHtml(any(), any());
        verify(processedPostRepository).save(argThat(p -> p.getContentLink() == null));
    }

    @Test
    void processAlbum_duplicateLink_skips() {
        var msg = createTextMessage(100L, 1L, "Nike Shoes https://shop.com");

        setupTemplate("{title}\n{link}", "[\"title\",\"link\"]");

        when(openAiExtractorService.extractFields(any(), any()))
                .thenReturn(Map.of("title", "Nike Shoes", "link", "https://shop.com"));
        when(processedPostRepository.existsByContentLink("https://shop.com")).thenReturn(true);

        service.processAlbum(List.of(msg), tdLibClient);

        verify(telegramPublisherService, never()).publishHtml(any(), any());
        verify(processedPostRepository, never()).save(any());
    }

    // ---- processAlbum() ----

    @Test
    void processAlbum_noTemplate_skips() {
        var msg = createTextMessage(100L, 1L, "Album text");
        when(userTemplateRepository.findCurrent()).thenReturn(Optional.empty());

        service.processAlbum(List.of(msg), tdLibClient);

        verify(telegramPublisherService, never()).publishHtml(any(), any());
    }

    @Test
    void processAlbum_withTemplate_extractsAndPublishes() {
        var msg = createTextMessage(100L, 1L, "Album text");

        setupTemplate("{title}", "[\"title\"]");

        when(openAiExtractorService.extractFields(eq("Album text"), eq(List.of("title"))))
                .thenReturn(Map.of("title", "Album text"));
        when(telegramPublisherService.publishHtml(eq("Album text"), any())).thenReturn(true);

        service.processAlbum(List.of(msg), tdLibClient);

        verify(processedPostRepository).save(any());
    }

    @Test
    void processAlbum_savesOneProcessedPost() {
        var msg1 = createTextMessage(100L, 1L, "Caption");
        var msg2 = createTextMessage(100L, 2L, "");

        setupTemplate("{title}", "[\"title\"]");

        when(openAiExtractorService.extractFields(any(), any()))
                .thenReturn(Map.of("title", "Caption"));
        when(telegramPublisherService.publishHtml(any(), any())).thenReturn(true);

        service.processAlbum(List.of(msg1, msg2), tdLibClient);

        verify(processedPostRepository, times(1)).save(any());
    }

    // ---- extractTextWithUrls ----

    @Test
    void extractTextWithUrls_plainText_returnsAsIs() {
        var ft = new TdApi.FormattedText("Hello world", new TdApi.TextEntity[0]);
        String result = service.extractTextWithUrls(ft);
        assertThat(result).isEqualTo("Hello world");
    }

    @Test
    void extractTextWithUrls_visibleUrl_notDuplicated() {
        var ft = new TdApi.FormattedText("Visit https://shop.com now", new TdApi.TextEntity[]{
                new TdApi.TextEntity(6, 16, new TdApi.TextEntityTypeTextUrl("https://shop.com"))
        });
        String result = service.extractTextWithUrls(ft);
        assertThat(result).isEqualTo("Visit https://shop.com now");
    }

    @Test
    void extractTextWithUrls_hiddenUrl_appended() {
        var ft = new TdApi.FormattedText("Buy here", new TdApi.TextEntity[]{
                new TdApi.TextEntity(0, 8, new TdApi.TextEntityTypeTextUrl("https://shop.com/product"))
        });
        String result = service.extractTextWithUrls(ft);
        assertThat(result).contains("Buy here");
        assertThat(result).contains("https://shop.com/product");
    }

    @Test
    void extractTextWithUrls_nullEntities_returnsPlainText() {
        var ft = new TdApi.FormattedText("Plain text", null);
        String result = service.extractTextWithUrls(ft);
        assertThat(result).isEqualTo("Plain text");
    }

    // ---- photo/video messages ----

    @Test
    void process_photoMessage_extractsCaption() {
        var msg = new TdApi.Message();
        msg.chatId = 100L;
        msg.id = 1L;
        var caption = new TdApi.FormattedText("Photo caption", new TdApi.TextEntity[0]);
        var photo = new TdApi.Photo();
        photo.sizes = new TdApi.PhotoSize[0];
        msg.content = new TdApi.MessagePhoto(photo, caption, false, false, false);

        setupTemplate("{title}", "[\"title\"]");

        when(openAiExtractorService.extractFields(eq("Photo caption"), eq(List.of("title"))))
                .thenReturn(Map.of("title", "Photo caption"));
        when(telegramPublisherService.publishHtml(any(), any())).thenReturn(true);

        service.process(msg, tdLibClient);

        verify(openAiExtractorService).extractFields(eq("Photo caption"), eq(List.of("title")));
    }

    @Test
    void process_videoMessage_extractsCaption() {
        var msg = new TdApi.Message();
        msg.chatId = 100L;
        msg.id = 1L;
        var caption = new TdApi.FormattedText("Video caption", new TdApi.TextEntity[0]);
        var msgVideo = new TdApi.MessageVideo();
        msgVideo.caption = caption;
        msg.content = msgVideo;

        setupTemplate("{title}", "[\"title\"]");

        when(openAiExtractorService.extractFields(eq("Video caption"), eq(List.of("title"))))
                .thenReturn(Map.of("title", "Video caption"));
        when(telegramPublisherService.publishHtml(any(), any())).thenReturn(true);

        service.process(msg, tdLibClient);

        verify(openAiExtractorService).extractFields(eq("Video caption"), eq(List.of("title")));
    }
}
