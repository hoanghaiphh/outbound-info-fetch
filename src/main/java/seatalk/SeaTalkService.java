package seatalk;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;

import tools.jackson.databind.ObjectMapper;

public class SeaTalkService {

    private static final String AUTH_URL = "https://openapi.seatalk.io/auth/app_access_token";
    private static final String SEATALK_GROUP_CHAT_URL = "https://openapi.seatalk.io/messaging/v2/group_chat";
    private static final String CONFIG_FILE_PATH = "creds/amon.properties";

    private String appId;
    private String appSecret;
    private String groupId;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private String cachedAccessToken;
    private Instant tokenExpiryTime = Instant.MIN;

    public SeaTalkService() {
        loadCredentials();

        this.httpClient = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    private void loadCredentials() {
        Properties props = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE_PATH)) {
            if (input == null) {
                throw new RuntimeException("Configuration file not found on classpath: " + CONFIG_FILE_PATH);
            }
            props.load(input);

            this.appId = props.getProperty("seatalk.app_id");
            this.appSecret = props.getProperty("seatalk.app_secret");
            this.groupId = props.getProperty("seatalk.group_id");

            if (appId == null || appSecret == null || groupId == null) {
                throw new IllegalArgumentException("Missing required configuration keys in " + CONFIG_FILE_PATH);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load configuration file: " + CONFIG_FILE_PATH, e);
        }
    }

    private synchronized void invalidateToken() {
        this.cachedAccessToken = null;
        this.tokenExpiryTime = Instant.MIN;
    }

    private synchronized String getAccessToken(boolean forceRefresh) {
        if (forceRefresh) {
            invalidateToken();
        }

        if (cachedAccessToken != null && Instant.now().isBefore(tokenExpiryTime.minusSeconds(60))) {
            return cachedAccessToken;
        }

        try {
            var authPayload = Map.of(
                    "app_id", appId,
                    "app_secret", appSecret
            );
            String jsonBody = objectMapper.writeValueAsString(authPayload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(AUTH_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                invalidateToken();
                throw new RuntimeException("SeaTalk Auth HTTP Error: " + response.statusCode() + " Body: " + response.body());
            }

            Map<?, ?> responseMap = objectMapper.readValue(response.body(), Map.class);

            if (responseMap.get("code") instanceof Number code && code.intValue() == 0) {
                this.cachedAccessToken = (String) responseMap.get("app_access_token");

                int expiresIn = 7200;
                if (responseMap.get("expire") instanceof Number exp) {
                    expiresIn = exp.intValue();
                }

                this.tokenExpiryTime = Instant.now().plusSeconds(expiresIn);
                return this.cachedAccessToken;
            } else {
                invalidateToken();
                throw new RuntimeException("Failed to obtain access token: " + response.body());
            }

        } catch (Exception e) {
            invalidateToken();
            throw new RuntimeException("Error fetching SeaTalk access token: " + e.getMessage(), e);
        }
    }

    public boolean sendMsgToGroup(String msg) {
        if (msg == null || msg.isBlank()) {
            throw new IllegalArgumentException("Message content cannot be null or empty.");
        }

        try {
            var message = Map.of(
                    "tag", "text",
                    "text", Map.of("format", 1, "content", msg)
            );
            executeSendMessageWithRetry(message);
            return true;
        } catch (Exception e) {
            System.err.println("[SeaTalkService System Error] " + e.getMessage());
            return false;
        }
    }

    public boolean sendImgToGroup(String imageBase64) {
        if (imageBase64 == null || imageBase64.strip().isEmpty()) {
            throw new IllegalArgumentException("Image Base64 string cannot be null or empty.");
        }

        try {
            var message = Map.of(
                    "tag", "image",
                    "image", Map.of("content", imageBase64)
            );
            executeSendMessageWithRetry(message);
            return true;
        } catch (Exception e) {
            System.err.println("[SeaTalkService System Error] " + e.getMessage());
            return false;
        }
    }

    private void executeSendMessageWithRetry(Map<String, Object> messagePayload) throws Exception {
        var payload = Map.of(
                "group_id", groupId,
                "message", messagePayload
        );
        String jsonPayload = objectMapper.writeValueAsString(payload);

        for (int attempt = 1; attempt <= 2; attempt++) {
            boolean forceRefresh = (attempt == 2);
            String currentToken = getAccessToken(forceRefresh);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SEATALK_GROUP_CHAT_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + currentToken)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();

            if (statusCode == 401 && attempt == 1) {
                System.err.println("[WARN] Token unauthorized (HTTP 401). Invalidating token and retrying...");
                invalidateToken();
                continue;
            }

            if (statusCode >= 200 && statusCode < 300) {
                Map<?, ?> responseMap = objectMapper.readValue(response.body(), Map.class);

                if (responseMap.get("code") instanceof Number code) {
                    int responseCode = code.intValue();

                    if (responseCode == 0) {
                        return;
                    }

                    if (attempt == 1) {
                        System.err.println("[WARN] SeaTalk API error code (" + responseCode + "). Invalidating token and retrying...");
                        invalidateToken();
                        continue;
                    }
                }
            }

            throw new RuntimeException("SeaTalk API error HTTP status: " + statusCode + ", Response: " + response.body());
        }
    }
}