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
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import seatalk.SeaTalkWsModels.*;

public class SeaTalkBotWebSocketClient implements WebSocket.Listener {

    private static final String DEFAULT_WS_URL = "wss://ws-openapi.haiserve.com/ws/bot";

    private final String appId;
    private final String appSecret;
    private final String wsUrl;
    private final ObjectMapper objectMapper;
    private final BiConsumer<String, Map<String, Object>> eventHandler;

    private WebSocket webSocket;
    private String registeredToken;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

    private final StringBuilder messageBuffer = new StringBuilder();

    public SeaTalkBotWebSocketClient(String appId, String appSecret,
                                     BiConsumer<String, Map<String, Object>> eventHandler) {
        this(appId, appSecret, DEFAULT_WS_URL, eventHandler);
    }

    public SeaTalkBotWebSocketClient(String appId, String appSecret, String wsUrl,
                                     BiConsumer<String, Map<String, Object>> eventHandler) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.wsUrl = wsUrl;
        this.eventHandler = eventHandler;
        this.objectMapper = new ObjectMapper();
    }

    public void connect() {
        HttpClient client = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(wsUrl), this)
                .join();
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
                case "pong" -> {}
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
            close();
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

    private void startHeartbeat(long intervalSeconds) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (registeredToken != null) {
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
        stopHeartbeat();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        System.err.println("[SeaTalk WS] Error: " + error.getMessage());
    }

    public void close() {
        stopHeartbeat();
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Closing").thenRun(() -> webSocket = null);
        }
    }

    private void stopHeartbeat() {
        scheduler.shutdownNow();
    }

    @SuppressWarnings("unchecked")
    private void handleIncomingEvent(String eventType, Map<String, Object> eventData, String groupId) {
        System.out.println("[SeaTalk WS] Received Event Type: " + eventType);

        if ("message_from_bot_subscriber".equals(eventType)) {
            Map<String, Object> message = (Map<String, Object>) eventData.get("message");
            if (message == null) return;

            String senderSeatalkId = (String) message.get("sender_seatalk_id");
            String msgType = (String) message.get("msg_type");

            if ("text".equals(msgType)) {
                Map<String, Object> textObj = (Map<String, Object>) message.get("text");
                String content = textObj != null ? ((String) textObj.get("content")).trim() : "";

                System.out.println("Message from " + senderSeatalkId + ": " + content);

                if ("test".equalsIgnoreCase(content)) {
                    String result = executeTestLogic();

                    SeaTalkService service = new SeaTalkService();
                    service.sendMsgToGroup(groupId, result);
                }
            }
        }
    }

    private String executeTestLogic() {
        System.out.println("[LOGIC] Đang chạy hàm test()...");
        return "Executing successfully!\nServer time: " + java.time.LocalDateTime.now();
    }
}