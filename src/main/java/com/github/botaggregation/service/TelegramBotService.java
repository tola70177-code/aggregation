package com.github.botaggregation.service;

import com.github.botaggregation.config.TelegramBotProperties;
import com.github.botaggregation.entity.DestinationChannel;
import com.github.botaggregation.entity.SourceChannel;
import com.github.botaggregation.entity.UserTemplate;
import com.github.botaggregation.repository.DestinationChannelRepository;
import com.github.botaggregation.repository.SourceChannelRepository;
import com.github.botaggregation.repository.UserTemplateRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.description.SetMyDescription;
import org.telegram.telegrambots.meta.api.methods.description.SetMyShortDescription;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
@Slf4j
public class TelegramBotService implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final int PAGE_SIZE = 8;

    private static final String MENU_DESCRIPTION = """
            Джерело постів — звідки беремо пости
            Канал для постів — куди відправляємо пости по шаблону
            Шаблон — на прикладі одного поста, вказуємо яку інформацію хочемо залишити і в якому форматі, після створення шаблону, відправляємо його в бота
            Вийти з акаунта — завершує роботу з ботом""";

    private final TelegramClient telegramClient;
    private final TelegramBotProperties botProperties;
    private final TdLibClientService tdLibClientService;
    private final SourceChannelRepository sourceChannelRepository;
    private final DestinationChannelRepository destinationChannelRepository;
    private final UserTemplateRepository userTemplateRepository;
    private final OpenAiExtractorService openAiExtractorService;
    private final ConcurrentHashMap<String, String> pendingAction = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> lastBotMessageId = new ConcurrentHashMap<>();

    public TelegramBotService(TelegramClient telegramClient,
                              TelegramBotProperties botProperties,
                              TdLibClientService tdLibClientService,
                              SourceChannelRepository sourceChannelRepository,
                              DestinationChannelRepository destinationChannelRepository,
                              UserTemplateRepository userTemplateRepository,
                              OpenAiExtractorService openAiExtractorService) {
        this.telegramClient = telegramClient;
        this.botProperties = botProperties;
        this.tdLibClientService = tdLibClientService;
        this.sourceChannelRepository = sourceChannelRepository;
        this.destinationChannelRepository = destinationChannelRepository;
        this.userTemplateRepository = userTemplateRepository;
        this.openAiExtractorService = openAiExtractorService;
    }

    @PostConstruct
    void setBotDescription() {
        try {
            String description = """
                    Бот для агрегації постів з Telegram каналів.

                    Автоматично відслідковує пости з обраних каналів, \
                    витягує потрібні дані за допомогою AI, \
                    очищує посилання від трекінгових кодів \
                    та публікує у ваш канал за заданим шаблоном.

                    Як почати:
                    1. Підключіть Telegram акаунт
                    2. Оберіть канали-джерела
                    3. Вкажіть канал для публікації
                    4. Задайте шаблон постів""";

            String shortDescription = "Агрегація постів з Telegram каналів з AI обробкою та публікацією у ваш канал";

            telegramClient.execute(SetMyDescription.builder()
                    .description(description)
                    .build());
            telegramClient.execute(SetMyShortDescription.builder()
                    .shortDescription(shortDescription)
                    .build());
            log.info("[BOT] Bot description set successfully");
        } catch (Exception e) {
            log.warn("[BOT] Failed to set bot description: {}", e.getMessage());
        }
    }

    @Override
    public String getBotToken() {
        return botProperties.getToken();
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        try {
            if (update.hasMessage()) {
                Message msg = update.getMessage();
                if (msg.hasText()) {
                    handleTextMessage(msg);
                } else if (hasPendingAction(msg) && getMessageText(msg) != null) {
                    handleTextMessage(msg);
                }
            } else if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
            }
        } catch (Exception e) {
            log.error("[BOT] Error handling update: {}", e.getMessage(), e);
        }
    }

    // ---- Text message routing ----

    private void handleTextMessage(Message message) throws Exception {
        String text = getMessageText(message);
        if (text == null) return;
        text = text.trim();
        String chatId = message.getChatId().toString();

        if ("/start".equals(text)) {
            pendingAction.remove(chatId);
            handleStartCommand(chatId);
            return;
        }

        String action = pendingAction.remove(chatId);
        if ("awaiting_phone".equals(action)) {
            handlePhoneInput(chatId, text);
            return;
        }
        if ("awaiting_code".equals(action)) {
            handleCodeInput(chatId, text);
            return;
        }
        if ("awaiting_password".equals(action)) {
            handlePasswordInput(chatId, text);
            return;
        }
        if ("awaiting_template".equals(action)) {
            String html = BotMessageConverter.toHtml(message);
            boolean hasImage = message.hasPhoto();
            saveGlobalTemplate(chatId, html, hasImage);
            return;
        }
    }

    // ---- Callback routing ----

    private void handleCallback(CallbackQuery callback) throws Exception {
        String data = callback.getData();
        String chatId = callback.getMessage().getChatId().toString();

        telegramClient.execute(AnswerCallbackQuery.builder()
                .callbackQueryId(callback.getId())
                .build());

        pendingAction.remove(chatId);

        if ("menu".equals(data) || "logout".equals(data)) {
            if ("logout".equals(data)) {
                handleLogout(chatId);
            } else {
                handleStartCommand(chatId);
            }
        } else if (tdLibClientService.getAuthState() != TdLibClientService.AuthState.READY) {
            // No active session — redirect to phone input instead of handling the action
            handleStartCommand(chatId);
        } else if ("sources".equals(data)) {
            showSourceSelection(chatId, 0);
        } else if (data.startsWith("src_p:")) {
            int page = Integer.parseInt(data.substring(6));
            showSourceSelection(chatId, page);
        } else if (data.startsWith("src_t:")) {
            String[] parts = data.substring(6).split(":");
            long channelId = Long.parseLong(parts[0]);
            int page = Integer.parseInt(parts[1]);
            toggleSource(chatId, channelId, page);
        } else if ("destination".equals(data)) {
            showDestinationSelection(chatId, 0);
        } else if (data.startsWith("dst_p:")) {
            int page = Integer.parseInt(data.substring(6));
            showDestinationSelection(chatId, page);
        } else if (data.startsWith("dst_s:")) {
            long channelId = Long.parseLong(data.substring(6));
            selectDestination(chatId, channelId);
        } else if ("template".equals(data)) {
            promptForTemplate(chatId);
        }
    }

    // ---- Start / Main Menu ----

    private void handleStartCommand(String chatId) throws Exception {
        var state = tdLibClientService.getAuthState();
        if (state == TdLibClientService.AuthState.READY) {
            send(SendMessage.builder()
                    .chatId(chatId)
                    .text("Оберіть дію:\n\n" + MENU_DESCRIPTION)
                    .replyMarkup(mainMenuButtons())
                    .build());
        } else {
            pendingAction.put(chatId, "awaiting_phone");
            send(SendMessage.builder()
                    .chatId(chatId)
                    .text("Для роботи бота, потрібно задати акаунт користувача, "
                            + "щоб відслідковувати канали. Введіть будь ласка номер телефону")
                    .build());
        }
    }

    private InlineKeyboardMarkup mainMenuButtons() {
        return new InlineKeyboardMarkup(List.of(
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Джерело постів").callbackData("sources").build()
                ),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Канал для постів").callbackData("destination").build()
                ),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Шаблон").callbackData("template").build()
                ),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Вийти з акаунта").callbackData("logout").build()
                )
        ));
    }

    // ---- Auth flow ----

    private void handleLogout(String chatId) throws Exception {
        tdLibClientService.logOutAndShutdown();
        pendingAction.put(chatId, "awaiting_phone");
        send(SendMessage.builder()
                .chatId(chatId)
                .text("Сесію завершено. Введіть будь ласка номер телефону для нового підключення")
                .build());
    }

    private void handlePhoneInput(String chatId, String phone) throws Exception {
        if (phone == null || phone.isBlank()) {
            pendingAction.put(chatId, "awaiting_phone");
            send(SendMessage.builder()
                    .chatId(chatId)
                    .text("Номер телефону не може бути порожнім. Введіть будь ласка номер телефону")
                    .build());
            return;
        }

        phone = phone.trim();
        if (!phone.startsWith("+")) {
            phone = "+" + phone;
        }

        tdLibClientService.startWithPhone(phone);

        pendingAction.put(chatId, "awaiting_code");
        send(SendMessage.builder()
                .chatId(chatId)
                .text("Ви маєте отримати код від телеграму, "
                        + "відправте його у форматі 1-2-3-4-5 (розділяйте кожну цифру знаком - ) для підтвердження входу.")
                .build());
    }

    private void handleCodeInput(String chatId, String code) throws Exception {
        String cleanCode = code.replaceAll("[^0-9]", "");
        if (cleanCode.isEmpty()) {
            pendingAction.put(chatId, "awaiting_code");
            send(SendMessage.builder()
                    .chatId(chatId)
                    .text("Не знайдено цифр. Відправте код у форматі 1-2-3-4-5")
                    .build());
            return;
        }

        tdLibClientService.submitAuthCode(cleanCode);

        var resultState = pollAuthState(10);

        if (resultState == TdLibClientService.AuthState.READY) {
            send(SendMessage.builder()
                    .chatId(chatId)
                    .text("Вітаю, ви успішно підключили акаунт до бота!\n\n" + MENU_DESCRIPTION)
                    .replyMarkup(mainMenuButtons())
                    .build());
        } else if (resultState == TdLibClientService.AuthState.NEED_PASSWORD) {
            pendingAction.put(chatId, "awaiting_password");
            send(SendMessage.builder()
                    .chatId(chatId)
                    .text("Ваш акаунт має двофакторну автентифікацію. Введіть будь ласка ваш пароль.")
                    .build());
        } else {
            pendingAction.put(chatId, "awaiting_code");
            send(SendMessage.builder()
                    .chatId(chatId)
                    .text("Нажаль щось пішло не так, спробуйте ввести код ще раз")
                    .build());
        }
    }

    private void handlePasswordInput(String chatId, String password) throws Exception {
        if (password == null || password.isBlank()) {
            pendingAction.put(chatId, "awaiting_password");
            send(SendMessage.builder()
                    .chatId(chatId)
                    .text("Пароль не може бути порожнім. Введіть будь ласка ваш пароль.")
                    .build());
            return;
        }

        tdLibClientService.submitAuthPassword(password.trim());

        var resultState = pollAuthState(10);

        if (resultState == TdLibClientService.AuthState.READY) {
            send(SendMessage.builder()
                    .chatId(chatId)
                    .text("Вітаю, ви успішно підключили акаунт до бота!\n\n" + MENU_DESCRIPTION)
                    .replyMarkup(mainMenuButtons())
                    .build());
        } else {
            pendingAction.put(chatId, "awaiting_password");
            send(SendMessage.builder()
                    .chatId(chatId)
                    .text("Нажаль щось пішло не так, спробуйте ввести пароль ще раз")
                    .build());
        }
    }

    private TdLibClientService.AuthState pollAuthState(int maxAttempts) {
        var initialState = tdLibClientService.getAuthState();
        for (int i = 0; i < maxAttempts; i++) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            var current = tdLibClientService.getAuthState();
            if (current != initialState) {
                return current;
            }
        }
        return tdLibClientService.getAuthState();
    }

    // ---- Source selection (multi-select toggle) ----

    private void showSourceSelection(String chatId, int page) throws Exception {
        List<Map<String, Object>> allChannels = tdLibClientService.getSubscribedChannels();

        if (allChannels.isEmpty()) {
            sendText(chatId, "Не знайдено жодного каналу, на який ви підписані.");
            return;
        }

        Set<Long> existingSourceIds = sourceChannelRepository.findAll().stream()
                .map(SourceChannel::getChannelId)
                .collect(Collectors.toSet());

        int totalPages = (allChannels.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        page = Math.max(0, Math.min(page, totalPages - 1));
        int from = page * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, allChannels.size());

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            var ch = allChannels.get(i);
            String title = (String) ch.get("title");
            long chId = ((Number) ch.get("channelId")).longValue();
            String label = existingSourceIds.contains(chId) ? "\u2705 " + title : title;
            rows.add(new InlineKeyboardRow(
                    InlineKeyboardButton.builder()
                            .text(label)
                            .callbackData("src_t:" + chId + ":" + page)
                            .build()
            ));
        }

        var navRow = new InlineKeyboardRow();
        if (page > 0) {
            navRow.add(InlineKeyboardButton.builder()
                    .text("\u2B05 Назад").callbackData("src_p:" + (page - 1)).build());
        }
        if (page < totalPages - 1) {
            navRow.add(InlineKeyboardButton.builder()
                    .text("Далі \u27A1").callbackData("src_p:" + (page + 1)).build());
        }
        if (!navRow.isEmpty()) {
            rows.add(navRow);
        }
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Назад").callbackData("menu").build()
        ));

        editOrSend(chatId, "Оберіть канали, з яких будемо тягнути пости\n\n"
                        + "Можна обрати декілька каналів, для відслідковування.\n\n"
                        + "Це всі канали, на які підписаний ваш акаунт, "
                        + "якщо хочете обрати інший канал, спочатку підпишіться користувачем на цей канал.",
                new InlineKeyboardMarkup(rows));
    }

    private void toggleSource(String chatId, long channelId, int page) throws Exception {
        var existing = sourceChannelRepository.findByChannelId(channelId);

        if (existing.isPresent()) {
            sourceChannelRepository.delete(existing.get());
        } else {
            String title = tdLibClientService.getSubscribedChannels().stream()
                    .filter(ch -> ((Number) ch.get("channelId")).longValue() == channelId)
                    .map(ch -> (String) ch.get("title"))
                    .findFirst()
                    .orElse(null);

            var channel = new SourceChannel();
            channel.setChannelId(channelId);
            channel.setChannelName(title);
            channel.setEnabled(true);
            sourceChannelRepository.save(channel);
        }

        tdLibClientService.loadMonitoredChannels();
        showSourceSelection(chatId, page);
    }

    // ---- Destination selection (single-select) ----

    private void showDestinationSelection(String chatId, int page) throws Exception {
        List<Map<String, Object>> allChannels = tdLibClientService.getSubscribedChannels();

        if (allChannels.isEmpty()) {
            sendText(chatId, "Не знайдено жодного каналу, на який ви підписані.");
            return;
        }

        Long currentDestId = destinationChannelRepository.findCurrent()
                .map(DestinationChannel::getChannelId)
                .orElse(null);

        int totalPages = (allChannels.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        page = Math.max(0, Math.min(page, totalPages - 1));
        int from = page * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, allChannels.size());

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            var ch = allChannels.get(i);
            String title = (String) ch.get("title");
            long chId = ((Number) ch.get("channelId")).longValue();
            String label = (currentDestId != null && currentDestId == chId)
                    ? "\u2705 " + title : title;
            rows.add(new InlineKeyboardRow(
                    InlineKeyboardButton.builder()
                            .text(label)
                            .callbackData("dst_s:" + chId)
                            .build()
            ));
        }

        var navRow = new InlineKeyboardRow();
        if (page > 0) {
            navRow.add(InlineKeyboardButton.builder()
                    .text("\u2B05 Назад").callbackData("dst_p:" + (page - 1)).build());
        }
        if (page < totalPages - 1) {
            navRow.add(InlineKeyboardButton.builder()
                    .text("Далі \u27A1").callbackData("dst_p:" + (page + 1)).build());
        }
        if (!navRow.isEmpty()) {
            rows.add(navRow);
        }
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Назад").callbackData("menu").build()
        ));

        editOrSend(chatId, "Оберіть канал для публікації постів\n\n"
                        + "Можна обрати лише один канал.\n\n"
                        + "Це всі канали, на які підписаний ваш акаунт, "
                        + "якщо хочете обрати інший канал, спочатку підпишіться користувачем на цей канал.",
                new InlineKeyboardMarkup(rows));
    }

    private void selectDestination(String chatId, long channelId) throws Exception {
        var existing = destinationChannelRepository.findCurrent();

        DestinationChannel channel;
        if (existing.isPresent()) {
            channel = existing.get();
            channel.setChannelId(channelId);
        } else {
            channel = new DestinationChannel();
            channel.setChannelId(channelId);
        }
        destinationChannelRepository.save(channel);

        // Re-show the destination list with the updated checkmark
        showDestinationSelection(chatId, 0);
    }

    // ---- Template ----

    private void promptForTemplate(String chatId) throws Exception {
        pendingAction.put(chatId, "awaiting_template");
        send(SendMessage.builder()
                .chatId(chatId)
                .text("Відправте будь ласка шаблон, як ви хочете бачити пости "
                        + "і з якими даними в своєму каналі")
                .replyMarkup(backButton())
                .build());
    }

    private void saveGlobalTemplate(String chatId, String text, boolean hasImage) throws Exception {
        var analysis = openAiExtractorService.analyzeTemplate(text);
        if (analysis == null) {
            send(SendMessage.builder()
                    .chatId(chatId)
                    .text("Не вдалось проаналізувати шаблон, спробуйте ще раз")
                    .replyMarkup(mainMenuButtons())
                    .build());
            return;
        }

        // Build the template by replacing example values with {field_name} placeholders
        // in the original HTML — this preserves all formatting
        // Skip Telegram links — they are static parts of the template
        String normalizedTemplate = text;
        List<String> activeFields = new ArrayList<>();
        for (var entry : analysis.examples().entrySet()) {
            String fieldName = entry.getKey();
            String exampleValue = entry.getValue();
            if (exampleValue != null && !exampleValue.isBlank()
                    && !exampleValue.contains("t.me/") && !exampleValue.contains("telegram.me/")) {
                normalizedTemplate = normalizedTemplate.replace(exampleValue, "{" + fieldName + "}");
                activeFields.add(fieldName);
            }
        }

        String fieldsJson;
        try {
            fieldsJson = new ObjectMapper().writeValueAsString(activeFields);
        } catch (Exception e) {
            log.error("[BOT] Failed to serialize fields: {}", e.getMessage());
            send(SendMessage.builder()
                    .chatId(chatId)
                    .text("Не вдалось проаналізувати шаблон, спробуйте ще раз")
                    .replyMarkup(mainMenuButtons())
                    .build());
            return;
        }

        var existing = userTemplateRepository.findCurrent();
        UserTemplate template;
        if (existing.isPresent()) {
            template = existing.get();
        } else {
            template = new UserTemplate();
        }
        template.setTemplateText(normalizedTemplate);
        template.setFields(fieldsJson);
        template.setHasImage(hasImage);
        userTemplateRepository.save(template);

        log.info("[BOT] Template saved: {}", normalizedTemplate);

        send(SendMessage.builder()
                .chatId(chatId)
                .text("Шаблон збережено \u2705\n\n"
                        + "Тепер коли в каналі для постів, будуть з'являтись нові пости, "
                        + "з яких можна дістати всі дані під ваш шаблон. "
                        + "Тоді в Джерелі постів, буде публікуватись пост з даними.")
                .replyMarkup(mainMenuButtons())
                .build());
    }

    // ---- Helpers ----

    private Message send(SendMessage msg) throws Exception {
        String chatId = msg.getChatId();
        Integer previous = lastBotMessageId.get(chatId);
        if (previous != null) {
            deleteMessage(chatId, previous);
        }
        Message sent = (Message) telegramClient.execute(msg);
        if (sent != null) {
            lastBotMessageId.put(chatId, sent.getMessageId());
        }
        return sent;
    }

    /**
     * Edits the existing bot message in place (text + buttons), avoiding the "flip" effect.
     * Falls back to delete+send if there's no previous message or editing fails.
     */
    private void editOrSend(String chatId, String text, InlineKeyboardMarkup markup) throws Exception {
        Integer previous = lastBotMessageId.get(chatId);
        if (previous != null) {
            try {
                telegramClient.execute(EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(previous)
                        .text(text)
                        .replyMarkup(markup)
                        .build());
                return;
            } catch (Exception e) {
                log.debug("[BOT] Failed to edit message, falling back to send: {}", e.getMessage());
            }
        }
        send(SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(markup)
                .build());
    }

    private void deleteMessage(String chatId, int messageId) {
        try {
            telegramClient.execute(DeleteMessage.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .build());
        } catch (Exception e) {
            log.debug("[BOT] Failed to delete message {} in chat {}: {}", messageId, chatId, e.getMessage());
        }
    }

    private void sendText(String chatId, String text) throws Exception {
        send(SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(mainMenuButtons())
                .build());
    }

    private InlineKeyboardMarkup backButton() {
        return new InlineKeyboardMarkup(List.of(
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Назад").callbackData("menu").build()
                )
        ));
    }

    private boolean hasPendingAction(Message message) {
        return pendingAction.containsKey(message.getChatId().toString());
    }

    private String getMessageText(Message message) {
        if (message.hasText()) return message.getText();
        if (message.getCaption() != null) return message.getCaption();
        return null;
    }
}
