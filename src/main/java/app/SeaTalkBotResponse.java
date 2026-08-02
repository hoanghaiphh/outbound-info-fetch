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

public class SeaTalkBotResponse {

    private static final String CONFIG_FILE_PATH = "creds/amon.properties";
    private static final SeaTalkService seatalk = new SeaTalkService();
    // private static final Pattern ARG_PATTERN = Pattern.compile("--(?<key>\\w+)='(?<value>[^']*)'");
    private static final Pattern ARG_PATTERN = Pattern.compile("--(?<key>\\w+)\\s*=\\s*'(?<value>[^']*)'");

    private static final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("SeatalkBot-Worker");
        return t;
    });

    public static void main(String[] args) {

        Properties props = new Properties();
        try (InputStream input = SeaTalkBotResponse.class.getClassLoader().getResourceAsStream(CONFIG_FILE_PATH)) {
            if (input == null) {
                return;
            }
            props.load(input);
        } catch (Exception e) {
            return;
        }

        String appId = props.getProperty("seatalk.app_id");
        String appSecret = props.getProperty("seatalk.app_secret");

        System.out.println("Initializing WebSocket Client for App ID: " + appId);

        SeaTalkBotWebSocketClient wsClient = new SeaTalkBotWebSocketClient(
                appId,
                appSecret,
                SeaTalkBotResponse::handleIncomingEvent
        );

        Runtime.getRuntime().addShutdownHook(new Thread(executor::shutdown));

        try {
            System.out.println("Connecting to WebSocket...");
            wsClient.connect();
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Application interrupted", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleIncomingEvent(String eventType, Map<String, Object> eventData) {
        if (eventData == null) return;

        Map<String, Object> eventObj = (Map<String, Object>) eventData.get("event");
        if (eventObj == null) return;

        String seatalkId = null;
        String email = null;
        String content = "";

        switch (eventType) {
            case "message_from_bot_subscriber": {
                Object seatalkIdObj = eventObj.get("seatalk_id");
                seatalkId = seatalkIdObj != null ? String.valueOf(seatalkIdObj) : null;
                email = (String) eventObj.get("email");

                Map<String, Object> messageObj = (Map<String, Object>) eventObj.get("message");
                if (messageObj != null && "text".equals(messageObj.get("tag"))) {
                    Map<String, Object> textObj = (Map<String, Object>) messageObj.get("text");
                    if (textObj != null && textObj.get("content") != null) {
                        content = ((String) textObj.get("content")).trim();
                    }
                }
                break;
            }

            case "new_mentioned_message_received_from_group_chat": {
                Map<String, Object> messageObj = (Map<String, Object>) eventObj.get("message");
                if (messageObj == null) return;

                Map<String, Object> senderObj = (Map<String, Object>) messageObj.get("sender");
                if (senderObj != null) {
                    Object seatalkIdObj = senderObj.get("seatalk_id");
                    seatalkId = seatalkIdObj != null ? String.valueOf(seatalkIdObj) : null;
                    email = (String) senderObj.get("email");
                }

                if ("text".equals(messageObj.get("tag"))) {
                    Map<String, Object> textObj = (Map<String, Object>) messageObj.get("text");
                    if (textObj != null && textObj.get("plain_text") != null) {
                        content = ((String) textObj.get("plain_text")).trim();

                        content = content.replaceAll("^@[^\\s]+\\s*", "").trim();
                    }
                }
                break;
            }

            default:
                return;
        }

        if (seatalkId != null && !content.isEmpty()) {
            System.out.printf("[New Message] Event: %s | From: %s (%s) | Content: %s%n",
                    eventType, email, seatalkId, content);

            final String finalSeatalkId = seatalkId;
            final String finalContent = content;

            executor.submit(() -> executeCommand(finalSeatalkId, finalContent));
        }
    }

    private static void executeCommand(String seatalkId, String content) {
        if (seatalkId == null) return;

        content = content.trim();

        if (content.toLowerCase().contains("reprint")) {
            executeRePrintCommand(content);
        } else if (content.toLowerCase().contains("backlog")) {
            executeBacklogCommand(content);
        } else if (content.equalsIgnoreCase("test")) {
            seatalk.sendMsgToGroup(AMON_GROUP_ID, "Don't ask us why we're taking such risks. Life often requires some excitement, joy, and anticipation.");
        } else {
            seatalk.sendMsgToGroup(AMON_GROUP_ID, "What the f*ck are you talking about?");
        }
    }

    private static void executeBacklogCommand(String cmd) {
        // backlog --from='2026/07/19 18:00:00' --to='2026/07/20 18:00:00'

        try {
            Matcher matcher = ARG_PATTERN.matcher(cmd);

            String begTime = null;
            String endTime = null;

            while (matcher.find()) {
                String key = matcher.group("key");
                String value = matcher.group("value");

                switch (key.toLowerCase()) {
                    case "from":
                        begTime = value;
                        break;
                    case "to":
                        endTime = value;
                        break;
                }
            }

            if (begTime == null || endTime == null) {
                seatalk.sendMsgToGroup(AMON_GROUP_ID, "Please input range of time!");
                return;
            }

            final String begTimeFinal = begTime;
            final String endTimeFinal = endTime;

            seatalk.sendMsgToGroup(AMON_GROUP_ID, "Im thinking ...\nPlease wait a second ...");

            Map<String, String> cookiesB = CookiesConfig.loadCookies(DEFAULT_USER, "VNDB");
            Map<String, String> cookiesL = CookiesConfig.loadCookies(DEFAULT_USER, "VNDL");

            synchronized (SeaTalkBotResponse.class) {
                CommonHelper.cleanUpDirectory(TMP_OUTPUT_DIR);
            }

            CompletableFuture<Void> taskB = CompletableFuture.runAsync(() -> {
                try {
                    ApiCalling.generateReportFile(begTimeFinal, endTimeFinal, cookiesB);
                    ApiCalling.downloadReportFile(cookiesB, "VNDB", TMP_OUTPUT_DIR);
                } catch (Exception e) {
                    throw new CompletionException("Failed to fetch VNDB report data!", e);
                }
            }, executor);

            CompletableFuture<Void> taskL = CompletableFuture.runAsync(() -> {
                try {
                    ApiCalling.generateReportFile(begTimeFinal, endTimeFinal, cookiesL);
                    ApiCalling.downloadReportFile(cookiesL, "VNDL", TMP_OUTPUT_DIR);
                } catch (Exception e) {
                    throw new CompletionException("Failed to fetch VNDL report data!", e);
                }
            }, executor);

            CompletableFuture.allOf(taskB, taskL).get(10, TimeUnit.MINUTES);

            String result = ReportImgGenerator.createReportImage(TMP_OUTPUT_DIR);

            seatalk.sendMsgToGroup(AMON_GROUP_ID, "Backlog:" +
                    "\nFrom: **" + begTime + "**" +
                    "\nTo: **" + endTime + "**");

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

    private static void executeRePrintCommand(String cmd) {
        // reprint --from='2026/07/19 18:00:00' --to='2026/07/20 18:00:00' --warehouse='L' --lmtracking='SPXVN062524848687'

        try {

            Matcher matcher = ARG_PATTERN.matcher(cmd);

            String begTime = null;
            String endTime = null;
            String warehouse = null;
            String lmTrackingNo = null;

            while (matcher.find()) {
                String key = matcher.group("key");
                String value = matcher.group("value");

                switch (key.toLowerCase()) {
                    case "from":
                        begTime = value;
                        break;
                    case "to":
                        endTime = value;
                        break;
                    case "warehouse":
                        warehouse = value;
                        break;
                    case "lmtracking":
                        lmTrackingNo = value;
                        break;
                }
            }

            if (begTime == null || endTime == null) {
                String[] timeRange = CommonHelper.getWorkingTimeRange(false); // todo: ???
                begTime = timeRange[0];
                endTime = timeRange[1];
            }

            if (lmTrackingNo == null) {
                seatalk.sendMsgToGroup(AMON_GROUP_ID, "Please input LM Tracking Number!");
                return;
            }

            if (warehouse == null) {
                seatalk.sendMsgToGroup(AMON_GROUP_ID, "Please input Warehouse!");
                return;
            }

            seatalk.sendMsgToGroup(AMON_GROUP_ID, "Im thinking ...\nPlease wait a second ...");

            Map<String, String> cookies;
            if (warehouse.equalsIgnoreCase("B")) {
                cookies = CookiesConfig.loadCookies(DEFAULT_USER, "VNDB");

            } else if (warehouse.equalsIgnoreCase("L")) {
                cookies = CookiesConfig.loadCookies(DEFAULT_USER, "VNDL");
            } else {
                seatalk.sendMsgToGroup(AMON_GROUP_ID, "Warehouse invalid!");
                return;
            }

            String result = ApiCalling.getRePrintOrderAsString(cookies, begTime, endTime, lmTrackingNo);

            seatalk.sendMsgToGroup(AMON_GROUP_ID, "Re-print Order in same task:" +
                    "\nFrom: **" + begTime + "**" +
                    "\nTo: **" + endTime + "**" +
                    "\nLM Tracking: **" + lmTrackingNo.toUpperCase() + "**" +
                    "\n\n" + result);

        } catch (Exception e) {
            System.err.println("[CRITICAL ERROR] Unhandled exception occurred in current cycle: " + e.getMessage());
            e.printStackTrace();
        }
    }
}