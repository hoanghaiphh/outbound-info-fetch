package gemini;

import app.SeaTalkBotResponse;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GeminiService {

    private static final Logger log = LogManager.getLogger(GeminiService.class);

    private static final String MODEL_NAME = "gemini-3.6-flash";
    private static final String KEY_FILE_PATH = "creds/gemini-api-key.txt";

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 1000;
    private final Random random = new Random();

    private final Client client;
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

    private static class InstanceHolder {
        private static final GeminiService INSTANCE = new GeminiService();
    }

    public static GeminiService getInstance() {
        return InstanceHolder.INSTANCE;
    }

    private GeminiService() {
        String apiKey = loadApiKeyFromResources(KEY_FILE_PATH);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("Token not found at: " + KEY_FILE_PATH);
        }

        this.client = Client.builder().apiKey(apiKey.trim()).build();

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    public CompletableFuture<String> askGemini(String prompt) {
        return CompletableFuture.supplyAsync(() -> executeWithRetry(prompt), executorService);
    }

    private String executeWithRetry(String prompt) {
        int attempt = 0;
        long backoff = INITIAL_BACKOFF_MS;

        while (true) {
            try {
                attempt++;
                GenerateContentResponse response = client.models.generateContent(MODEL_NAME, prompt, null);
                return response.text();
            } catch (Exception e) {
                if (attempt >= MAX_RETRIES) {
                    log.error("[GEMINI FAILED]: Tried {} time(s) and failed. Error: {}",
                            attempt, e.getMessage());
                    return "[GEMINI ERROR]: " + e.getMessage();
                }

                long jitter = random.nextInt(250);
                long sleepTime = backoff + jitter;

                log.error("[GEMINI RETRY]: Tried {} time(s) failed ({}). Try again in {} ms...",
                        attempt, e.getMessage(), sleepTime);

                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return "[GEMINI ERROR]: Interrupted during retry backoff.";
                }

                backoff *= 2;
            }
        }
    }

    private String loadApiKeyFromResources(String resourcePath) {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) return null;

            try (Scanner scanner = new Scanner(inputStream, StandardCharsets.UTF_8)) {
                return scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void shutdown() {
        if (!executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}