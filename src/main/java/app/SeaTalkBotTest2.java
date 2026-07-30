package app;

import general.CommonHelper;
import seatalk.ReportImgGenerator;
import seatalk.SeaTalkBotWebSocketClient;
import seatalk.SeaTalkService;
import wms.ApiCalling;
import wms.CookiesConfig;

import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static general.GlobalConstants.*;
import static general.GlobalConstants.DEFAULT_USER;

public class SeaTalkBotTest2 {

    private static final String CONFIG_FILE_PATH = "creds/amon.properties";
    private static final SeaTalkService seatalk = new SeaTalkService();

    private static final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("SeatalkBot-Worker");
        return t;
    });

    public static void main(String[] args) {

        Properties props = new Properties();
        try (InputStream input = SeaTalkBotTest.class.getClassLoader().getResourceAsStream(CONFIG_FILE_PATH)) {
            if (input == null) {
                return;
            }
            props.load(input);
        } catch (Exception e) {
            return;
        }

        String appId = props.getProperty("seatalk.app_id");
        String appSecret = props.getProperty("seatalk.app_secret");

        System.out.println(">>> Initializing WebSocket Client for App ID: " + appId);

        SeaTalkBotWebSocketClient wsClient = new SeaTalkBotWebSocketClient(
                appId,
                appSecret,
                SeaTalkBotTest2::handleIncomingEvent
        );

        try {
            System.out.println("Connecting to WebSocket...");
            wsClient.connect();
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleIncomingEvent(String eventType, Map<String, Object> eventData) {
        if ("message_from_bot_subscriber".equals(eventType)) {

            Map<String, Object> eventObj = (Map<String, Object>) eventData.get("event");
            if (eventObj == null) return;

            Object seatalkIdObj = eventObj.get("seatalk_id");
            String seatalkId = seatalkIdObj != null ? String.valueOf(seatalkIdObj) : null;
            String email = (String) eventObj.get("email");

            Map<String, Object> messageObj = (Map<String, Object>) eventObj.get("message");
            if (messageObj == null) return;

            String tag = (String) messageObj.get("tag");

            if ("text".equals(tag)) {
                Map<String, Object> textObj = (Map<String, Object>) messageObj.get("text");
                String content = textObj != null ? ((String) textObj.get("content")).trim() : "";

                System.out.printf("[New message] From %s (%s): %s%n", email, seatalkId, content);
                executeCommand(seatalkId, content);
            }
        }
    }

    private static void executeCommand(String seatalkId, String content) {
        String reformatContent = content.toLowerCase().trim();

        if (seatalkId != null && reformatContent.startsWith("order")) {
            executeOrderCommand(reformatContent);
            if (!executor.isShutdown()) {
                executor.shutdownNow();
            }
        }

    }

    private static void executeOrderCommand(String cmd) {
        // order --from='2026/07/19 18:00:00' --to='2026/07/20 18:00:00'

        try {
            Pattern pattern = Pattern.compile("--from='([^']*)'\\s+--to='([^']*)'");
            Matcher matcher = pattern.matcher(cmd);

            String begTime;
            String endTime;

            if (matcher.find()) {
                begTime = matcher.group(1);
                endTime = matcher.group(2);
            } else {
                endTime = null;
                begTime = null;
            }

            if (!CookiesConfig.isCookiesValid(DEFAULT_USER, "VNDB")
                    || !CookiesConfig.isCookiesValid(DEFAULT_USER, "VNDL")) {
                CookiesConfig.loginAndSaveCookies(DEFAULT_USER, DEFAULT_PW);
            }

            Map<String, String> cookiesB = CookiesConfig.loadCookies(DEFAULT_USER, "VNDB");
            Map<String, String> cookiesL = CookiesConfig.loadCookies(DEFAULT_USER, "VNDL");

            CommonHelper.cleanUpDirectory(OUTPUT_DIR);

            CompletableFuture<Void> taskB = CompletableFuture.runAsync(() -> {
                try {
                    ApiCalling.generateReportFile(begTime, endTime, cookiesB);
                    ApiCalling.downloadReportFile(cookiesB, "VNDB");
                } catch (Exception e) {
                    throw new CompletionException("Failed to fetch VNDB report data!", e);
                }
            }, executor);

            CompletableFuture<Void> taskL = CompletableFuture.runAsync(() -> {
                try {
                    ApiCalling.generateReportFile(begTime, endTime, cookiesL);
                    ApiCalling.downloadReportFile(cookiesL, "VNDL");
                } catch (Exception e) {
                    throw new CompletionException("Failed to fetch VNDL report data!", e);
                }
            }, executor);

            CompletableFuture.allOf(taskB, taskL).get(10, TimeUnit.MINUTES);

            String result = ReportImgGenerator.createReportImage();

            seatalk.sendMsgToGroup(AMON_GROUP_ID, "Specific Request:\nFrom: " + begTime + "\nTo: " + endTime + "\nGenerated by: " + DEFAULT_USER);
            seatalk.sendImgToGroup(AMON_GROUP_ID, result);

        } catch (TimeoutException e) {
            System.err.println("[ERROR] Execution timed out (exceeded 10 minutes threshold). Skipping current cycle.");

        } catch (ExecutionException e) {
            Throwable rootCause = e.getCause() != null ? e.getCause() : e;
            System.err.println("[ERROR] Task execution failed: " + rootCause.getMessage());
            rootCause.printStackTrace();

        } catch (InterruptedException e) {
            System.err.println("[WARN] Worker thread execution was interrupted during synchronization wait.");
            Thread.currentThread().interrupt();

        } catch (Exception e) {
            System.err.println("[CRITICAL ERROR] Unhandled exception occurred in current cycle: " + e.getMessage());
            e.printStackTrace();
        }
    }

}