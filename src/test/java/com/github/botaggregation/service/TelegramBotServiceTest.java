package com.github.botaggregation.service;

import com.github.botaggregation.config.TelegramBotProperties;
import com.github.botaggregation.repository.DestinationChannelRepository;
import com.github.botaggregation.repository.SourceChannelRepository;
import com.github.botaggregation.repository.UserTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramBotServiceTest {

    @Mock private TelegramClient telegramClient;
    @Mock private TdLibClientService tdLibClientService;
    @Mock private SourceChannelRepository sourceChannelRepository;
    @Mock private DestinationChannelRepository destinationChannelRepository;
    @Mock private UserTemplateRepository userTemplateRepository;
    @Mock private OpenAiExtractorService openAiExtractorService;

    private TelegramBotService service;

    @BeforeEach
    void setUp() throws Exception {
        var botProperties = new TelegramBotProperties();
        botProperties.setToken("test-bot-token");
        service = new TelegramBotService(
                telegramClient, botProperties, tdLibClientService,
                sourceChannelRepository, destinationChannelRepository,
                userTemplateRepository, openAiExtractorService
        );

        Message sentMessage = mock(Message.class);
        lenient().when(sentMessage.getMessageId()).thenReturn(1);
        lenient().doReturn(sentMessage).when(telegramClient).execute(any(SendMessage.class));
    }

    private Update textUpdate(String text) {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn(text);
        when(message.getChatId()).thenReturn(123L);
        lenient().when(message.getCaption()).thenReturn(null);
        return update;
    }

    private Update callbackUpdate(String data) {
        Update update = mock(Update.class);
        when(update.hasMessage()).thenReturn(false);
        when(update.hasCallbackQuery()).thenReturn(true);
        CallbackQuery callback = mock(CallbackQuery.class);
        when(update.getCallbackQuery()).thenReturn(callback);
        when(callback.getData()).thenReturn(data);
        when(callback.getId()).thenReturn("qid");
        Message callbackMsg = mock(Message.class);
        when(callback.getMessage()).thenReturn(callbackMsg);
        when(callbackMsg.getChatId()).thenReturn(123L);
        return update;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private String findSentText() {
        try {
            ArgumentCaptor<BotApiMethod> captor = ArgumentCaptor.forClass(BotApiMethod.class);
            verify(telegramClient, atLeast(1)).execute(captor.capture());
            return captor.getAllValues().stream()
                    .filter(SendMessage.class::isInstance)
                    .map(SendMessage.class::cast)
                    .map(SendMessage::getText)
                    .findFirst()
                    .orElse("");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void getBotToken_returnsConfiguredToken() {
        assertThat(service.getBotToken()).isEqualTo("test-bot-token");
    }

    @Test
    void consume_startCommand_notAuthenticated_sendsPhonePrompt() {
        when(tdLibClientService.getAuthState()).thenReturn(TdLibClientService.AuthState.WAITING);
        service.consume(textUpdate("/start"));
        assertThat(findSentText()).contains("номер телефону");
    }

    @Test
    void consume_startCommand_authenticated_sendsMainMenu() {
        when(tdLibClientService.getAuthState()).thenReturn(TdLibClientService.AuthState.READY);
        service.consume(textUpdate("/start"));
        assertThat(findSentText()).contains("Оберіть дію");
    }

    @Test
    void consume_logoutCallback_callsLogout() {
        service.consume(callbackUpdate("logout"));
        verify(tdLibClientService).logOutAndShutdown();
        assertThat(findSentText()).contains("Сесію завершено");
    }

    @Test
    void consume_templateCallback_whenAuthenticated_sendsPrompt() {
        when(tdLibClientService.getAuthState()).thenReturn(TdLibClientService.AuthState.READY);
        service.consume(callbackUpdate("template"));
        assertThat(findSentText()).contains("шаблон");
    }

    @Test
    void consume_callbackWhenNotAuthenticated_redirectsToStart() {
        when(tdLibClientService.getAuthState()).thenReturn(TdLibClientService.AuthState.WAITING);
        service.consume(callbackUpdate("sources"));
        assertThat(findSentText()).contains("номер телефону");
    }

    @Test
    void consume_phoneInput_startsClient() {
        when(tdLibClientService.getAuthState()).thenReturn(TdLibClientService.AuthState.WAITING);
        service.consume(textUpdate("/start"));
        service.consume(textUpdate("380123456789"));
        verify(tdLibClientService).startWithPhone("+380123456789");
    }

    @Test
    void consume_startCommand_resetsPendingAction() {
        when(tdLibClientService.getAuthState()).thenReturn(TdLibClientService.AuthState.WAITING);
        // First /start sets pending action to awaiting_phone
        service.consume(textUpdate("/start"));
        // Second /start should NOT be treated as phone input — it should reset
        service.consume(textUpdate("/start"));
        // Should show phone prompt again, not "Не знайдено цифр"
        assertThat(findSentText()).contains("номер телефону");
        verify(tdLibClientService, never()).startWithPhone(any());
    }
}
