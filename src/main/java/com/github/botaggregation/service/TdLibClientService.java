package com.github.botaggregation.service;

import com.github.botaggregation.config.TdLibProperties;
import com.github.botaggregation.repository.SourceChannelRepository;
import it.tdlight.Init;
import it.tdlight.client.APIToken;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.ClientInteraction;
import it.tdlight.client.InputParameter;
import it.tdlight.client.ParameterInfo;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.SimpleTelegramClientFactory;
import it.tdlight.client.TDLibSettings;
import it.tdlight.jni.TdApi;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class TdLibClientService {

    private final TdLibProperties tdLibProperties;
    private final SourceChannelRepository sourceChannelRepository;
    private final MessageProcessingService messageProcessingService;

    private SimpleTelegramClientFactory clientFactory;
    private SimpleTelegramClient client;

    private final Set<Long> monitoredChannelIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, List<TdApi.Message>> albumBuffer = new ConcurrentHashMap<>();

    // Auth flow state
    public enum AuthState { WAITING, NEED_CODE, NEED_PASSWORD, READY, ERROR }
    private volatile AuthState authState = AuthState.WAITING;
    private volatile String authError;
    private volatile CompletableFuture<String> pendingCodeFuture;
    private volatile CompletableFuture<String> pendingPasswordFuture;

    @PostConstruct
    public void init() {
        loadMonitoredChannels();
        log.info("[TDLIB] Initialized. Waiting for phone number via bot.");
    }

    public void startWithPhone(String phoneNumber) {
        logOutAndShutdown();

        authState = AuthState.WAITING;
        authError = null;
        pendingCodeFuture = null;
        pendingPasswordFuture = null;

        log.info("[TDLIB] Starting TDLib client for phone: {}****",
                phoneNumber.substring(0, Math.min(4, phoneNumber.length())));

        Thread.startVirtualThread(() -> startClient(phoneNumber));
    }

    /**
     * Logs out the current TDLib session (terminates the session on Telegram servers)
     * and shuts down the client. Also deletes local session data so a fresh login
     * is required for the next account.
     */
    public void logOutAndShutdown() {
        try {
            if (client != null && authState == AuthState.READY) {
                log.info("[TDLIB] Logging out current session...");
                client.send(new TdApi.LogOut()).get(15, TimeUnit.SECONDS);
                log.info("[TDLIB] Logged out successfully");
            }
        } catch (Exception e) {
            log.warn("[TDLIB] LogOut failed (will force shutdown): {}", e.getMessage());
        }

        shutdown();
        deleteSessionData();
    }

    private void deleteSessionData() {
        try {
            var dbDir = Path.of(tdLibProperties.getDatabaseDirectory()).toFile();
            if (dbDir.exists()) {
                deleteRecursive(dbDir);
                log.info("[TDLIB] Deleted session data: {}", dbDir.getAbsolutePath());
            }
        } catch (Exception e) {
            log.warn("[TDLIB] Failed to delete session data: {}", e.getMessage());
        }
    }

    private void deleteRecursive(java.io.File file) {
        if (file.isDirectory()) {
            var children = file.listFiles();
            if (children != null) {
                for (var child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    private void startClient(String phoneNumber) {
        try {
            Init.init();

            // Reduce TDLib internal logging to errors only (avoids flooding Railway logs)
            it.tdlight.Log.setVerbosityLevel(1);

            var apiToken = new APIToken(tdLibProperties.getApiId(), tdLibProperties.getApiHash());
            var settings = TDLibSettings.create(apiToken);
            settings.setDatabaseDirectoryPath(Path.of(tdLibProperties.getDatabaseDirectory()));
            settings.setDownloadedFilesDirectoryPath(Path.of(tdLibProperties.getFilesDirectory()));

            clientFactory = new SimpleTelegramClientFactory();
            var clientBuilder = clientFactory.builder(settings);

            // Custom client interaction for REST-based auth (code/password via API)
            clientBuilder.setClientInteraction(new RestClientInteraction());

            // Register update handlers on the builder before build
            clientBuilder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, this::handleAuthStateUpdate);
            clientBuilder.addUpdateHandler(TdApi.UpdateNewMessage.class, this::handleNewMessage);

            // Build with phone number auth
            client = clientBuilder.build(AuthenticationSupplier.user(phoneNumber));

            log.info("[TDLIB] Client initialized, waiting for authorization...");
        } catch (Exception e) {
            log.error("[TDLIB] Failed to initialize client: {}", e.getMessage(), e);
            authState = AuthState.ERROR;
            authError = e.getMessage();
        }
    }

    @PreDestroy
    public void shutdown() {
        try {
            if (client != null) {
                client.sendClose();
            }
            if (clientFactory != null) {
                clientFactory.close();
            }
            log.info("[TDLIB] Client shut down");
        } catch (Exception e) {
            log.warn("[TDLIB] Error during shutdown: {}", e.getMessage());
        }
    }

    public void loadMonitoredChannels() {
        monitoredChannelIds.clear();
        sourceChannelRepository.findAllByEnabledTrue()
                .forEach(ch -> monitoredChannelIds.add(ch.getChannelId()));
        log.info("[TDLIB] Monitoring {} channels: {}", monitoredChannelIds.size(), monitoredChannelIds);
    }

    public void submitAuthCode(String code) {
        var future = pendingCodeFuture;
        if (future != null) {
            future.complete(code);
        }
    }

    public void submitAuthPassword(String password) {
        var future = pendingPasswordFuture;
        if (future != null) {
            future.complete(password);
        }
    }

    public AuthState getAuthState() {
        return authState;
    }

    public String getAuthError() {
        return authError;
    }

    private void handleAuthStateUpdate(TdApi.UpdateAuthorizationState update) {
        var state = update.authorizationState;
        log.info("[TDLIB] Auth state: {}", state.getClass().getSimpleName());

        if (state instanceof TdApi.AuthorizationStateReady) {
            authState = AuthState.READY;
            log.info("[TDLIB] Authorized and ready");
        } else if (state instanceof TdApi.AuthorizationStateClosed) {
            log.info("[TDLIB] Session closed");
        }
    }

    private void handleNewMessage(TdApi.UpdateNewMessage update) {
        var message = update.message;
        long chatId = message.chatId;

        if (!monitoredChannelIds.contains(chatId)) {

            log.info("[TDLIB] Ignoring message from unmonitored chat {} (monitoring: {})", chatId, monitoredChannelIds);
            return;
        }

        long albumId = message.mediaAlbumId;

        if (albumId != 0) {
            boolean isFirst = !albumBuffer.containsKey(albumId);
            albumBuffer.computeIfAbsent(albumId, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(message);
            log.info("[TDLIB] Buffered album message {}/{} (albumId={})", chatId, message.id, albumId);

            if (isFirst) {
                Thread.startVirtualThread(() -> processAlbumAfterDelay(albumId));
            }
        } else {
            log.info("[TDLIB] New message from monitored channel {}: msgId={}", chatId, message.id);
            Thread.startVirtualThread(() -> {
                try {
                    messageProcessingService.process(message, this);
                } catch (Exception e) {
                    log.error("[TDLIB] Failed to process message {} from chat {}: {}",
                            message.id, chatId, e.getMessage(), e);
                }
            });
        }
    }

    private void processAlbumAfterDelay(long albumId) {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        var albumMessages = albumBuffer.remove(albumId);
        if (albumMessages == null || albumMessages.isEmpty()) {
            return;
        }

        try {
            messageProcessingService.processAlbum(albumMessages, this);
        } catch (Exception e) {
            log.error("[TDLIB] Failed to process album {}: {}", albumId, e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> getSubscribedChannels() {
        if (client == null || authState != AuthState.READY) {
            return List.of();
        }

        try {
            var chatsResult = client.send(new TdApi.GetChats(new TdApi.ChatListMain(), 200))
                    .get(30, TimeUnit.SECONDS);

            if (!(chatsResult instanceof TdApi.Chats chats)) {
                return List.of();
            }

            List<Map<String, Object>> result = new ArrayList<>();
            for (long chatId : chats.chatIds) {
                var chatResult = client.send(new TdApi.GetChat(chatId))
                        .get(10, TimeUnit.SECONDS);
                if (chatResult instanceof TdApi.Chat chat
                        && chat.type instanceof TdApi.ChatTypeSupergroup supergroup) {
                    result.add(Map.of(
                            "channelId", chat.id,
                            "title", chat.title,
                            "isChannel", supergroup.isChannel
                    ));
                }
            }
            return result;
        } catch (Exception e) {
            log.error("[TDLIB] Failed to get subscribed channels: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public TdApi.File downloadFileSync(int fileId) {
        try {
            var result = client.send(new TdApi.DownloadFile(fileId, 1, 0, 0, true))
                    .get(60, TimeUnit.SECONDS);
            if (result instanceof TdApi.File file) {
                return file;
            }
        } catch (Exception e) {
            log.error("[TDLIB] Failed to download file {}: {}", fileId, e.getMessage());
        }
        return null;
    }

    /**
     * Custom ClientInteraction that returns CompletableFutures for code/password,
     * allowing submission via REST API instead of console.
     */
    private class RestClientInteraction implements ClientInteraction {

        @Override
        public CompletableFuture<String> onParameterRequest(InputParameter parameter, ParameterInfo parameterInfo) {
            return switch (parameter) {
                case ASK_CODE -> {
                    authState = AuthState.NEED_CODE;
                    log.info("[TDLIB] Verification code required. Submit via POST /api/auth/code");
                    var future = new CompletableFuture<String>();
                    pendingCodeFuture = future;
                    yield future;
                }
                case ASK_PASSWORD -> {
                    authState = AuthState.NEED_PASSWORD;
                    log.info("[TDLIB] 2FA password required. Submit via POST /api/auth/password");
                    var future = new CompletableFuture<String>();
                    pendingPasswordFuture = future;
                    yield future;
                }
                default -> CompletableFuture.completedFuture("");
            };
        }
    }
}
