package seatalk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

import tools.jackson.databind.ObjectMapper;

public class SeatalkService {

    private static final String TOKEN_URL = "https://cookie-vnw-default-rtdb.firebaseio.com/Token_XX/value.json";
    private static final String SEATALK_GROUP_CHAT_URL = "https://openapi.seatalk.io/messaging/v2/group_chat";
    private static final String GROUP_ID = "MDI5NTU5MzE4NTU2";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SeatalkService() {
        this.httpClient = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    private String fetchLatestToken() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Firebase HTTP Error: " + response.statusCode());
            }

            Object jsonRaw = objectMapper.readValue(response.body(), Object.class);
            if (jsonRaw instanceof Map<?, ?> data && data.get("app_access_token") instanceof String accessToken) {
                return accessToken;
            }

            throw new RuntimeException("Invalid JSON structure or 'app_access_token' missing. Response: " + response.body());
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch access token from Firebase! Reason: " + e.getMessage(), e);
        }
    }

    public void sendMsgToGroup(String msg) {
        try {
            var message = Map.of(
                    "tag", "text",
                    "text", Map.of("format", 1, "content", msg)
            );
            executeSendMessageWithRetry(message);
        } catch (Exception e) {
            System.err.println("[SeatalkService System Error] " + e.getMessage());
        }
    }

    public void sendImgToGroup(String imageBase64) {
        if (imageBase64 == null || imageBase64.strip().isEmpty()) {
            throw new IllegalArgumentException("Image Base64 string cannot be empty!");
        }

        try {
            var message = Map.of(
                    "tag", "image",
                    "image", Map.of("content", imageBase64)
            );
            executeSendMessageWithRetry(message);
        } catch (Exception e) {
            System.err.println("[SeatalkService System Error] " + e.getMessage());
        }
    }

    private void executeSendMessageWithRetry(Map<String, Object> messagePayload) throws Exception {
        var payload = Map.of(
                "group_id", GROUP_ID,
                "message", messagePayload
        );
        String jsonPayload = objectMapper.writeValueAsString(payload);

        for (int attempt = 1; attempt <= 2; attempt++) {

            if (attempt == 2) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }

            String currentToken = fetchLatestToken();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SEATALK_GROUP_CHAT_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + currentToken)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();

            if (statusCode >= 200 && statusCode < 300) {
                return;
            }

            if (statusCode == 401 && attempt == 1) {
                System.err.println("[WARN] Token unauthorized (401) on attempt 1. Waiting for external sync and retrying...");
                continue;
            }

            throw new RuntimeException("Seatalk API returned error status: " + statusCode + ", Response: " + response.body());
        }
    }

}