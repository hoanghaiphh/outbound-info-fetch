package seatalk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import seatalk.SeaTalkWsModels.*;

public class SeaTalkBotWebSocketClient implements WebSocket.Listener {

    private static final String DEFAULT_WS_URL = "wss://ws-openapi.haiserve.com/ws/bot";

    private final String appId;
    private final String appSecret;
    private final String wsUrl;
    private final ObjectMapper objectMapper;
    private final java.util.function.BiConsumer<String, Map<String, Object>> eventHandler;

    private WebSocket webSocket;
    private String registeredToken;

    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private final AtomicBoolean isClosedManual = new AtomicBoolean(false);

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> heartbeatTask;

    private final StringBuilder messageBuffer = new StringBuilder();

    public SeaTalkBotWebSocketClient(String appId, String appSecret,
                                     java.util.function.BiConsumer<String, Map<String, Object>> eventHandler) {
        this(appId, appSecret, DEFAULT_WS_URL, eventHandler);
    }

    public SeaTalkBotWebSocketClient(String appId, String appSecret, String wsUrl,
                                     java.util.function.BiConsumer<String, Map<String, Object>> eventHandler) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.wsUrl = wsUrl;
        this.eventHandler = eventHandler;
        this.objectMapper = new ObjectMapper();
        initScheduler();
    }

    private synchronized void initScheduler() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        }
    }

    public synchronized void connect() {
        if (isClosedManual.get()) return;
        if (isConnecting.get()) return;

        isConnecting.set(true);
        initScheduler();

        System.out.println("[SeaTalk WS] Connecting to " + wsUrl + "...");

        try {
            HttpClient client = HttpClient.newBuilder()
                    .executor(Executors.newVirtualThreadPerTaskExecutor())
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();

            client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(URI.create(wsUrl), this)
                    .whenComplete((ws, error) -> {
                        isConnecting.set(false);
                        if (error != null) {
                            System.err.println("[SeaTalk WS] Connection build failed: " + error.getMessage());
                            scheduleReconnect();
                        }
                    });
        } catch (Exception e) {
            isConnecting.set(false);
            System.err.println("[SeaTalk WS] Failed to initiate connection: " + e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (isClosedManual.get()) return;

        stopHeartbeat();
        registeredToken = null;

        System.out.println("[SeaTalk WS] Will attempt reconnect in 5 seconds...");
        scheduler.schedule(() -> {
            System.out.println("[SeaTalk WS] Attempting reconnecting...");
            connect();
        }, 5, TimeUnit.SECONDS);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        this.webSocket = webSocket;
        System.out.println("[SeaTalk WS] Connected. Sending register...");
        webSocket.request(1);

        Envelope regEnvelope = new Envelope("register", Header.forRegister(appId, appSecret));
        sendEnvelope(regEnvelope);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        messageBuffer.append(data);

        if (last) {
            String fullMessage = messageBuffer.toString();
            messageBuffer.setLength(0);
            handleIncomingFrame(fullMessage);
        }

        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    private void handleIncomingFrame(String jsonStr) {
        try {
            Envelope env = objectMapper.readValue(jsonStr, Envelope.class);

            switch (env.cmd) {
                case "register" -> handleRegisterResponse(env);
                case "event" -> handleEvent(env);
                case "kick" -> {
                    System.err.println("[SeaTalk WS] Session kicked by server: " + env.message);
                    close();
                }
                case "pong" -> {
                }
                default -> System.out.println("[SeaTalk WS] Received unknown CMD: " + env.cmd);
            }
        } catch (Exception e) {
            System.err.println("[SeaTalk WS] Error parsing frame: " + e.getMessage());
        }
    }

    private void handleRegisterResponse(Envelope env) {
        boolean isCodeOk = (env.code == null || env.code == 0);

        if (isCodeOk && env.header != null && env.header.token != null && !env.header.token.isBlank()) {
            this.registeredToken = env.header.token;
            System.out.println("[SeaTalk WS] Register successful! Token acquired: " + registeredToken);

            double intervalSeconds = 15.0;
            if (env.data != null) {
                try {
                    RegisterSettings settings = objectMapper.convertValue(env.data, RegisterSettings.class);
                    if (settings.heartbeatInterval > 0) {
                        intervalSeconds = settings.heartbeatInterval;
                    }
                } catch (Exception e) {
                    System.out.println("[SeaTalk WS] Using default heartbeat interval: 15s");
                }
            }

            startHeartbeat((long) intervalSeconds);
        } else {
            System.err.println("[SeaTalk WS] Register failed: " + env.message + " (Code: " + env.code + ")");
            scheduleReconnect();
        }
    }

    @SuppressWarnings("unchecked")
    private void handleEvent(Envelope env) {
        if (env.header != null && env.header.callbackId != null) {
            ack(env.header.callbackId);
        }

        if (env.data != null && eventHandler != null) {
            Map<String, Object> eventData = objectMapper.convertValue(env.data, Map.class);
            String eventType = (String) eventData.get("event_type");

            eventHandler.accept(eventType, eventData);
        }
    }

    private synchronized void startHeartbeat(long intervalSeconds) {
        stopHeartbeat();

        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (registeredToken != null && webSocket != null) {
                    sendEnvelope(new Envelope("ping", Header.forPing(registeredToken)));
                }
            } catch (Exception e) {
                System.err.println("[SeaTalk WS] Ping failed: " + e.getMessage());
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    public void ack(String callbackId) {
        if (registeredToken == null) return;
        sendEnvelope(new Envelope("ack", Header.forAck(registeredToken, callbackId)));
    }

    private synchronized void sendEnvelope(Envelope envelope) {
        try {
            if (envelope.header == null) envelope.header = new Header();
            if (envelope.header.rid == null || envelope.header.rid.isBlank()) {
                envelope.header.rid = UUID.randomUUID().toString().replace("-", "");
            }

            String jsonPayload = objectMapper.writeValueAsString(envelope);
            if (webSocket != null) {
                webSocket.sendText(jsonPayload, true);
            }
        } catch (Exception e) {
            System.err.println("[SeaTalk WS] Send failed: " + e.getMessage());
        }
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        System.out.println("[SeaTalk WS] Closed: " + statusCode + " / " + reason);
        messageBuffer.setLength(0);

        if (!isClosedManual.get()) {
            scheduleReconnect();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        System.err.println("[SeaTalk WS] Error occurred: " + error.getMessage());
        if (!isClosedManual.get()) {
            scheduleReconnect();
        }
    }

    public void close() {
        isClosedManual.set(true);
        stopHeartbeat();
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Closing").thenRun(() -> webSocket = null);
        }
    }

    private synchronized void stopHeartbeat() {
        if (heartbeatTask != null && !heartbeatTask.isCancelled()) {
            heartbeatTask.cancel(true);
        }
    }
}