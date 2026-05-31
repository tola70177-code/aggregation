package com.github.botaggregation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.botaggregation.config.TelegramBotProperties;
import com.github.botaggregation.entity.DestinationChannel;
import com.github.botaggregation.entity.PostTemplate;
import com.github.botaggregation.entity.SourceChannel;
import com.github.botaggregation.repository.DestinationChannelRepository;
import com.github.botaggregation.repository.PostTemplateRepository;
import com.github.botaggregation.repository.SourceChannelRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Slf4j
public class TelegramBotService implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final int PAGE_SIZE = 8;
    private static final Pattern INVITE_LINK = Pattern.compile(
            "https?://t\\.me/(\\+[A-Za-z0-9_-]+|joinchat/[A-Za-z0-9_-]+)");
    private static final Pattern PUBLIC_LINK = Pattern.compile(
            "https?://t\\.me/([A-Za-z]\\w{3,})");

    private final TelegramClient telegramClient;
    private final TelegramBotProperties botProperties;
    private final TdLibClientService tdLibClientService;
    private final SourceChannelRepository sourceChannelRepository;
    private final DestinationChannelRepository destinationChannelRepository;
    private final PostTemplateRepository postTemplateRepository;
    private final OpenAiExtractorService openAiExtractorService;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, String> pendingAction = new ConcurrentHashMap<>();

    public TelegramBotService(TelegramClient telegramClient,
                              TelegramBotProperties botProperties,
                              TdLibClientService tdLibClientService,
                              SourceChannelRepository sourceChannelRepository,
                              DestinationChannelRepository destinationChannelRepository,
                              PostTemplateRepository postTemplateRepository,
                              OpenAiExtractorService openAiExtractorService,
                              ObjectMapper objectMapper) {
        this.telegramClient = telegramClient;
        this.botProperties = botProperties;
        this.tdLibClientService = tdLibClientService;
        this.sourceChannelRepository = sourceChannelRepository;
        this.destinationChannelRepository = destinationChannelRepository;
        this.postTemplateRepository = postTemplateRepository;
        this.openAiExtractorService = openAiExtractorService;
        this.objectMapper = objectMapper;
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

        String action = pendingAction.remove(chatId);
        if ("awaiting_template".equals(action)) {
            saveTemplateText(chatId, text);
            return;
        }
        if ("awaiting_result".equals(action)) {
            saveResultText(chatId, message);
            return;
        }

        if ("/start".equals(text)) {
            sendMainMenu(chatId);
            return;
        }

        Matcher inviteMatcher = INVITE_LINK.matcher(text);
        if (inviteMatcher.find()) {
            handleInviteLink(chatId, "https://t.me/" + inviteMatcher.group(1));
            return;
        }

        Matcher publicMatcher = PUBLIC_LINK.matcher(text);
        if (publicMatcher.find()) {
            handlePublicLink(chatId, publicMatcher.group(1));
        }
    }

    // ---- Callback routing ----

    private void handleCallback(CallbackQuery callback) throws Exception {
        String data = callback.getData();
        String chatId = callback.getMessage().getChatId().toString();

        telegramClient.execute(AnswerCallbackQuery.builder()
                .callbackQueryId(callback.getId())
                .build());

        if ("menu".equals(data)) {
            sendMainMenu(chatId);
        } else if (data.startsWith("my:")) {
            int page = Integer.parseInt(data.substring(3));
            showMyChannels(chatId, page);
        } else if ("src".equals(data)) {
            showSetSource(chatId, 0);
        } else if (data.startsWith("sp:")) {
            int page = Integer.parseInt(data.substring(3));
            showSetSource(chatId, page);
        } else if (data.startsWith("ss:")) {
            long channelId = Long.parseLong(data.substring(3));
            saveSource(chatId, channelId);
        } else if ("rsrc".equals(data)) {
            showRemoveSource(chatId, 0);
        } else if (data.startsWith("rp:")) {
            int page = Integer.parseInt(data.substring(3));
            showRemoveSource(chatId, page);
        } else if (data.startsWith("rs:")) {
            long channelId = Long.parseLong(data.substring(3));
            removeSource(chatId, channelId);
        } else if ("dst".equals(data)) {
            showSetDestination(chatId, 0);
        } else if (data.startsWith("dp:")) {
            int page = Integer.parseInt(data.substring(3));
            showSetDestination(chatId, page);
        } else if (data.startsWith("sd:")) {
            long channelId = Long.parseLong(data.substring(3));
            saveDestination(chatId, channelId);
        } else if ("rd".equals(data)) {
            removeDestination(chatId);
        } else if ("tpl".equals(data)) {
            pendingAction.put(chatId, "awaiting_template");
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Send me the template post (example of source format):")
                    .replyMarkup(backButton())
                    .build());
        } else if ("res".equals(data)) {
            pendingAction.put(chatId, "awaiting_result");
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Send me a text instruction describing what to keep from the template and what to change:")
                    .replyMarkup(backButton())
                    .build());
        }
    }

    // ---- Main Menu ----

    private void sendMainMenu(String chatId) throws Exception {
        telegramClient.execute(SendMessage.builder()
                .chatId(chatId)
                .text("Bot Aggregation \u2014 Main Menu")
                .replyMarkup(mainMenuKeyboard())
                .build());
    }

    private InlineKeyboardMarkup mainMenuKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("My Channels").callbackData("my:0").build()
                ),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Set Source").callbackData("src").build(),
                        InlineKeyboardButton.builder().text("Remove Source").callbackData("rsrc").build()
                ),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Set Destination").callbackData("dst").build(),
                        InlineKeyboardButton.builder().text("Remove Destination").callbackData("rd").build()
                ),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Template").callbackData("tpl").build(),
                        InlineKeyboardButton.builder().text("Result").callbackData("res").build()
                )
        ));
    }

    // ---- My Channels (informational, paginated) ----

    private void showMyChannels(String chatId, int page) throws Exception {
        List<Map<String, Object>> channels = tdLibClientService.getSubscribedChannels();

        if (channels.isEmpty()) {
            sendText(chatId, "No subscribed channels found.");
            return;
        }

        int totalPages = (channels.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        page = Math.max(0, Math.min(page, totalPages - 1));
        int from = page * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, channels.size());

        var sb = new StringBuilder();
        sb.append("Subscribed Channels (page ").append(page + 1).append("/").append(totalPages).append("):\n\n");
        for (int i = from; i < to; i++) {
            var ch = channels.get(i);
            sb.append(ch.get("title")).append("\nID: ").append(ch.get("channelId")).append("\n\n");
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        var navRow = new InlineKeyboardRow();
        if (page > 0) {
            navRow.add(InlineKeyboardButton.builder().text("\u2B05 Prev").callbackData("my:" + (page - 1)).build());
        }
        if (page < totalPages - 1) {
            navRow.add(InlineKeyboardButton.builder().text("Next \u27A1").callbackData("my:" + (page + 1)).build());
        }
        if (!navRow.isEmpty()) {
            rows.add(navRow);
        }
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Back").callbackData("menu").build()
        ));

        telegramClient.execute(SendMessage.builder()
                .chatId(chatId)
                .text(sb.toString())
                .replyMarkup(new InlineKeyboardMarkup(rows))
                .build());
    }

    // ---- Set Source (channel buttons, paginated) ----

    private void showSetSource(String chatId, int page) throws Exception {
        List<Map<String, Object>> allChannels = tdLibClientService.getSubscribedChannels();

        Set<Long> existingSourceIds = sourceChannelRepository.findAll().stream()
                .map(SourceChannel::getChannelId)
                .collect(Collectors.toSet());

        List<Map<String, Object>> available = allChannels.stream()
                .filter(ch -> !existingSourceIds.contains(((Number) ch.get("channelId")).longValue()))
                .toList();

        if (available.isEmpty()) {
            sendText(chatId, "All subscribed channels are already added as sources.");
            return;
        }

        int totalPages = (available.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        page = Math.max(0, Math.min(page, totalPages - 1));
        int from = page * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, available.size());

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            var ch = available.get(i);
            String title = (String) ch.get("title");
            long chId = ((Number) ch.get("channelId")).longValue();
            rows.add(new InlineKeyboardRow(
                    InlineKeyboardButton.builder().text(title).callbackData("ss:" + chId).build()
            ));
        }

        var navRow = new InlineKeyboardRow();
        if (page > 0) {
            navRow.add(InlineKeyboardButton.builder().text("\u2B05 Prev").callbackData("sp:" + (page - 1)).build());
        }
        if (page < totalPages - 1) {
            navRow.add(InlineKeyboardButton.builder().text("Next \u27A1").callbackData("sp:" + (page + 1)).build());
        }
        if (!navRow.isEmpty()) {
            rows.add(navRow);
        }
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Back").callbackData("menu").build()
        ));

        String headerText = "Select a channel to add as source";
        if (totalPages > 1) {
            headerText += " (page " + (page + 1) + "/" + totalPages + ")";
        }
        headerText += ":";

        telegramClient.execute(SendMessage.builder()
                .chatId(chatId)
                .text(headerText)
                .replyMarkup(new InlineKeyboardMarkup(rows))
                .build());
    }

    // ---- Save source (from button click) ----

    private void saveSource(String chatId, long channelId) throws Exception {
        if (sourceChannelRepository.existsByChannelId(channelId)) {
            sendText(chatId, "This channel is already added as a source.");
            return;
        }

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

        tdLibClientService.loadMonitoredChannels();

        String confirmText = "Source added: " + (title != null ? title : String.valueOf(channelId))
                + "\nID: " + channelId;

        sendText(chatId, confirmText);
    }

    // ---- Remove Source (channel buttons, paginated) ----

    private void showRemoveSource(String chatId, int page) throws Exception {
        List<SourceChannel> sources = sourceChannelRepository.findAll();

        if (sources.isEmpty()) {
            sendText(chatId, "No source channels configured.");
            return;
        }

        int totalPages = (sources.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        page = Math.max(0, Math.min(page, totalPages - 1));
        int from = page * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, sources.size());

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            var src = sources.get(i);
            String label = src.getChannelName() != null ? src.getChannelName() : String.valueOf(src.getChannelId());
            rows.add(new InlineKeyboardRow(
                    InlineKeyboardButton.builder().text(label).callbackData("rs:" + src.getChannelId()).build()
            ));
        }

        var navRow = new InlineKeyboardRow();
        if (page > 0) {
            navRow.add(InlineKeyboardButton.builder().text("\u2B05 Prev").callbackData("rp:" + (page - 1)).build());
        }
        if (page < totalPages - 1) {
            navRow.add(InlineKeyboardButton.builder().text("Next \u27A1").callbackData("rp:" + (page + 1)).build());
        }
        if (!navRow.isEmpty()) {
            rows.add(navRow);
        }
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Back").callbackData("menu").build()
        ));

        String headerText = "Select a source to remove";
        if (totalPages > 1) {
            headerText += " (page " + (page + 1) + "/" + totalPages + ")";
        }
        headerText += ":";

        telegramClient.execute(SendMessage.builder()
                .chatId(chatId)
                .text(headerText)
                .replyMarkup(new InlineKeyboardMarkup(rows))
                .build());
    }

    private void removeSource(String chatId, long channelId) throws Exception {
        var source = sourceChannelRepository.findByChannelId(channelId);

        if (source.isEmpty()) {
            sendText(chatId, "Source not found.");
            return;
        }

        String name = source.get().getChannelName();
        sourceChannelRepository.delete(source.get());
        tdLibClientService.loadMonitoredChannels();

        String confirmText = "Source removed: " + (name != null ? name : String.valueOf(channelId))
                + "\nID: " + channelId;

        sendText(chatId, confirmText);
    }

    // ---- Set Destination (channel buttons, paginated) ----

    private void showSetDestination(String chatId, int page) throws Exception {
        List<Map<String, Object>> channels = tdLibClientService.getSubscribedChannels();

        if (channels.isEmpty()) {
            sendText(chatId, "No subscribed channels found.");
            return;
        }

        int totalPages = (channels.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        page = Math.max(0, Math.min(page, totalPages - 1));
        int from = page * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, channels.size());

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            var ch = channels.get(i);
            String title = (String) ch.get("title");
            long chId = ((Number) ch.get("channelId")).longValue();
            rows.add(new InlineKeyboardRow(
                    InlineKeyboardButton.builder().text(title).callbackData("sd:" + chId).build()
            ));
        }

        var navRow = new InlineKeyboardRow();
        if (page > 0) {
            navRow.add(InlineKeyboardButton.builder().text("\u2B05 Prev").callbackData("dp:" + (page - 1)).build());
        }
        if (page < totalPages - 1) {
            navRow.add(InlineKeyboardButton.builder().text("Next \u27A1").callbackData("dp:" + (page + 1)).build());
        }
        if (!navRow.isEmpty()) {
            rows.add(navRow);
        }
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Back").callbackData("menu").build()
        ));

        String headerText = "Select a channel as destination";
        if (totalPages > 1) {
            headerText += " (page " + (page + 1) + "/" + totalPages + ")";
        }
        headerText += ":";

        telegramClient.execute(SendMessage.builder()
                .chatId(chatId)
                .text(headerText)
                .replyMarkup(new InlineKeyboardMarkup(rows))
                .build());
    }

    private void saveDestination(String chatId, long channelId) throws Exception {
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

        String title = tdLibClientService.getSubscribedChannels().stream()
                .filter(ch -> ((Number) ch.get("channelId")).longValue() == channelId)
                .map(ch -> (String) ch.get("title"))
                .findFirst()
                .orElse(null);

        String confirmText = "Destination set: " + (title != null ? title : String.valueOf(channelId))
                + "\nID: " + channelId;

        sendText(chatId, confirmText);
    }

    // ---- Remove Destination ----

    private void removeDestination(String chatId) throws Exception {
        var destination = destinationChannelRepository.findCurrent();

        if (destination.isEmpty()) {
            sendText(chatId, "No destination configured.");
            return;
        }

        destinationChannelRepository.delete(destination.get());

        sendText(chatId, "Destination removed.");
    }

    // ---- Link handlers ----

    private void handleInviteLink(String chatId, String link) throws Exception {
        var info = tdLibClientService.checkChatInviteLink(link);
        if (info == null) {
            sendText(chatId, "Failed to resolve invite link.");
            return;
        }

        if (info.chatId == 0) {
            sendText(chatId, "You are not subscribed to this channel.");
            return;
        }

        saveSourceFromLink(chatId, info.chatId, info.title);
    }

    private void handlePublicLink(String chatId, String username) throws Exception {
        var chat = tdLibClientService.searchPublicChat(username);
        if (chat == null) {
            sendText(chatId, "Channel not found: @" + username);
            return;
        }

        boolean subscribed = tdLibClientService.getSubscribedChannels().stream()
                .anyMatch(ch -> ((Number) ch.get("channelId")).longValue() == chat.id);

        if (!subscribed) {
            sendText(chatId, "You are not subscribed to this channel.");
            return;
        }

        saveSourceFromLink(chatId, chat.id, chat.title);
    }

    private void saveSourceFromLink(String chatId, long channelId, String title) throws Exception {
        if (sourceChannelRepository.existsByChannelId(channelId)) {
            sendText(chatId, "This channel is already added as a source.");
            return;
        }

        var channel = new SourceChannel();
        channel.setChannelId(channelId);
        channel.setChannelName(title);
        channel.setEnabled(true);
        sourceChannelRepository.save(channel);

        tdLibClientService.loadMonitoredChannels();

        sendText(chatId, "Source added: " + (title != null ? title : String.valueOf(channelId))
                + "\nID: " + channelId);
    }

    // ---- Template / Result handlers ----

    private void saveTemplateText(String chatId, String text) throws Exception {
        var existing = postTemplateRepository.findCurrent();
        PostTemplate template;
        if (existing.isPresent()) {
            template = existing.get();
        } else {
            template = new PostTemplate();
        }
        template.setTemplateText(text);
        postTemplateRepository.save(template);

        sendText(chatId, "Template saved.");
    }

    private void saveResultText(String chatId, Message message) throws Exception {
        String instruction = getMessageText(message);
        if (instruction == null) return;
        instruction = instruction.trim();

        // Template must be set first
        var existing = postTemplateRepository.findCurrent();
        if (existing.isEmpty() || existing.get().getTemplateText() == null
                || existing.get().getTemplateText().isBlank()) {
            sendText(chatId, "Set the Template first (example of source post), then set the Result.");
            return;
        }

        PostTemplate template = existing.get();

        // Call AI: analyze template + instruction → fields + output_template
        JsonNode analysis = openAiExtractorService.analyzeInstruction(
                template.getTemplateText(), instruction);
        if (analysis == null) {
            sendText(chatId, "Failed to analyze instruction. Please try again.");
            return;
        }

        JsonNode fieldsNode = analysis.path("fields");
        JsonNode outputTemplateNode = analysis.path("output_template");

        if (fieldsNode.isMissingNode() || !fieldsNode.isObject() || fieldsNode.isEmpty()) {
            sendText(chatId, "No fields detected. Please try a different instruction.");
            return;
        }

        String resultHtml = outputTemplateNode.asText("");
        if (resultHtml.isBlank()) {
            sendText(chatId, "Failed to generate output template. Please try again.");
            return;
        }

        // Build fieldNames (label→lineNumber) and extract example values from template lines
        var fieldLines = new java.util.LinkedHashMap<String, Integer>();
        var fieldExamples = new java.util.LinkedHashMap<String, String>();
        String[] templateLines = template.getTemplateText().split("\n", -1);

        var fields = fieldsNode.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String label = entry.getKey();
            int lineNum = entry.getValue().asInt(0);
            if (lineNum > 0) {
                fieldLines.put(label, lineNum);
                if (lineNum <= templateLines.length) {
                    fieldExamples.put(label, templateLines[lineNum - 1].trim());
                }
            }
        }

        if (fieldLines.isEmpty()) {
            sendText(chatId, "No valid field mappings found. Please try again.");
            return;
        }

        String fieldNamesJson = objectMapper.writeValueAsString(fieldLines);
        String fieldExamplesJson = objectMapper.writeValueAsString(fieldExamples);

        template.setResultText(instruction);
        template.setResultHtml(resultHtml);
        template.setFieldNames(fieldNamesJson);
        template.setFieldExamples(fieldExamplesJson);
        postTemplateRepository.save(template);

        // Build readable summary
        var summary = new StringBuilder("Result saved.\nFields:");
        for (var entry : fieldLines.entrySet()) {
            summary.append("\n  ").append(entry.getKey())
                    .append(" ← line ").append(entry.getValue());
        }
        sendText(chatId, summary.toString());
    }

    // ---- Helpers ----

    private void sendText(String chatId, String text) throws Exception {
        telegramClient.execute(SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(mainMenuKeyboard())
                .build());
    }

    private InlineKeyboardMarkup backButton() {
        return new InlineKeyboardMarkup(List.of(
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Back").callbackData("menu").build()
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
