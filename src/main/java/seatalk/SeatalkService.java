package seatalk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class SeatalkService {

    private static final String TOKEN_URL = "https://cookie-vnw-default-rtdb.firebaseio.com/Token_XX/value.json";
    private static final String SEATALK_GROUP_CHAT_URL = "https://openapi.seatalk.io/messaging/v2/group_chat";
    private static final String GROUP_ID = "MDI5NTU5MzE4NTU2";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private volatile String token;

    public SeatalkService() {
        this.httpClient = HttpClient.newBuilder().build();
        this.objectMapper = new ObjectMapper();
    }

    private synchronized String fetchNewTokenFromServer() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_URL))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Firebase HTTP Error: " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode tokenNode = root.get("app_access_token");

            if (tokenNode != null && tokenNode.isTextual()) {
                this.token = tokenNode.asText();
                return this.token;
            }

            throw new RuntimeException("Missing 'app_access_token' field in Firebase response.");
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve token from Firebase: " + e.getMessage(), e);
        }
    }

    private String getOrFetchToken() {
        String currentToken = this.token;
        if (currentToken == null) {
            return fetchNewTokenFromServer();
        }
        return currentToken;
    }

    public void sendMsgToGroup(String msg) {
        try {
            var message = Map.of(
                    "tag", "text",
                    "text", Map.of("format", 1, "content", msg)
            );
            executeSendMessageWithRetry(message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send text message to Seatalk.", e);
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
            throw new RuntimeException("Failed to send image to Seatalk.", e);
        }
    }

    private void executeSendMessageWithRetry(Map<String, Object> messagePayload) throws Exception {
        var payload = Map.of(
                "group_id", GROUP_ID,
                "message", messagePayload
        );
        String jsonPayload = objectMapper.writeValueAsString(payload);

        for (int attempt = 1; attempt <= 2; attempt++) {
            String currentToken = getOrFetchToken();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SEATALK_GROUP_CHAT_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + currentToken)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();

            if (statusCode >= 200 && statusCode < 300) {
                return;
            }

            if (statusCode == 401) {
                System.err.println("[WARN] Token unauthorized (401). Invalidating cache and retrying...");
                this.token = null;
                if (attempt == 1) {
                    continue;
                }
            }

            throw new RuntimeException("Seatalk API returned error status: " + statusCode
                    + ", Response: " + response.body());
        }
    }

}