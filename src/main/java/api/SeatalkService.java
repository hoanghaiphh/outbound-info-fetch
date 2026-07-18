package api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.Executors;

import tools.jackson.databind.ObjectMapper;

public class SeatalkService {

    private static final String TOKEN_URL = "https://cookie-vnw-default-rtdb.firebaseio.com/Token_XX/value.json";
    private static final String SEATALK_GROUP_CHAT_URL = "https://openapi.seatalk.io/messaging/v2/group_chat";
//    private static final String GROUP_ID = "MzA3OTM5OTA1OTc5";
    private static final String GROUP_ID = "MDI5NTU5MzE4NTU2";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private volatile String token;

    public SeatalkService() {
        this.httpClient = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        this.objectMapper = new ObjectMapper();
    }

    private synchronized String getOrFetchToken() {
        if (this.token != null) {
            return this.token;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_URL))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Firebase HTTP Error: " + response.statusCode());
            }

            Object jsonRaw = objectMapper.readValue(response.body(), Object.class);
            if (jsonRaw instanceof Map<?, ?> data && data.get("app_access_token") instanceof String accessToken) {
                this.token = accessToken;
                return this.token;
            }

            throw new RuntimeException("Invalid JSON structure or 'app_access_token' missing. Response: "
                    + response.body());
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
            executeSendMessage(message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send text message to Seatalk! Content snippet: " + msg, e);
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
            executeSendMessage(message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send image to Seatalk!", e);
        }
    }

    private void executeSendMessage(Map<String, Object> messagePayload) throws Exception {
        var payload = Map.of(
                "group_id", GROUP_ID,
                "message", messagePayload
        );

        String jsonPayload = objectMapper.writeValueAsString(payload);
        String currentToken = getOrFetchToken();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SEATALK_GROUP_CHAT_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + currentToken)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            if (response.statusCode() == 401) {
                this.token = null;
            }
            throw new RuntimeException("Seatalk API returned error status: " + response.statusCode()
                    + ", Response: " + response.body());
        }
    }

}