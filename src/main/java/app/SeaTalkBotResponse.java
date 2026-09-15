package app;

import gemini.GeminiService;
import general.AutoModeConfig;
import general.CommonHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import general.ReportImgGenerator;
import seatalk.SeaTalkBotWebSocketClient;
import seatalk.SeaTalkService;
import wms.ApiCalling;
import wms.CookiesConfig;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static general.GlobalConstants.*;

public class SeaTalkBotResponse {

    private static final Logger log = LogManager.getLogger(SeaTalkBotResponse.class);

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

        log.info("Initializing WebSocket Client for App ID: {}", appId);

        SeaTalkBotWebSocketClient wsClient = new SeaTalkBotWebSocketClient(
                appId,
                appSecret,
                SeaTalkBotResponse::handleIncomingEvent
        );

        Runtime.getRuntime().addShutdownHook(new Thread(executor::shutdown));

        try {
            log.info("Connecting to WebSocket...");
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
        String groupId = null;
        String threadId = null;

        switch (eventType) {
            /*case "message_from_bot_subscriber": {
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
            }*/

            case "new_mentioned_message_received_from_group_chat": {

                // get Group ID
                Object groupIdObj = eventObj.get("group_id");
                groupId = groupIdObj != null ? String.valueOf(groupIdObj) : null;

                Map<String, Object> messageObj = (Map<String, Object>) eventObj.get("message");
                if (messageObj == null) return;

                // get Thread ID
                Object messageIdObj = messageObj.get("message_id");
                String messageId = messageIdObj != null ? String.valueOf(messageIdObj) : null;

                Object threadIdObj = messageObj.get("thread_id");
                threadId = threadIdObj != null ? String.valueOf(threadIdObj) : null;

                if (threadId == null || threadId.trim().isEmpty()) {
                    threadId = messageId;
                }

                // get other info
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
                        // content = content.replaceAll("^@[^\\s]+\\s*", "").trim(); // start
                        // content = content.replaceAll("(^@[^\\s]+\\s*|\\s*@[^\\s]+$)", "").trim(); // start + end
                        content = content.replaceAll("\\s*@[^\\s]+", "").trim(); // everywhere
                    }
                }
                break;
            }

            default:
                return;
        }

        if (groupId == null) groupId = AMON_GROUP_ID;

        if (seatalkId != null && !content.isEmpty()) {
            log.info("[New Message] Event: {} | Group: {} | Thread: {}\n\tFrom: {} ({})\n\tContent: {}",
                    eventType, groupId, threadId, email, seatalkId, content);

            final String finalSeatalkId = seatalkId;
            final String finalContent = content;
            final String finalGroupId = groupId;
            final String finalThreadId = threadId;

            executor.submit(() -> executeCommand(finalSeatalkId, finalContent, finalGroupId, finalThreadId));
        }
    }

    private static void executeCommand(String seatalkId, String content, String groupId, String threadId) {
        if (seatalkId == null) return;

        try {
            content = content.trim();

            if (content.toLowerCase().contains("ask-ai")) {
                executeAskAICommand(content, groupId, threadId);
            } else if (content.toLowerCase().contains("backlog")) {
                executeBacklogCommand(content, groupId, threadId);
            } else if (content.toLowerCase().contains("reprint")) {
                executeRePrintCommand(content, groupId, threadId);
            } else if (content.toLowerCase().contains("switch-mode")) {
                executeSwitchAutoModeCommand(content, groupId, threadId);
            } else {
                seatalk.sendMsgToGroup(
                        groupId,
                        "Don't ask us why we're taking such risks. Life often requires some excitement, joy, and anticipation.",
                        threadId);
            }
        } catch (Exception e) {
            seatalk.sendMsgToGroup(
                    groupId,
                    e.getMessage() + "\nServer busy at the moment!\nPlease try again.",
                    threadId);
        }
    }

    private static void executeBacklogCommand(String cmd, String groupId, String threadId) {
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
                seatalk.sendMsgToGroup(groupId, "Please input range of time!", threadId);
                return;
            }

            final String begTimeFinal = begTime;
            final String endTimeFinal = endTime;

            seatalk.sendMsgToGroup(groupId, "Im thinking ...\nPlease wait a second ...", threadId);

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

            String result = ReportImgGenerator.createReportImage(TMP_OUTPUT_DIR, begTime, endTime);

            /*seatalk.sendMsgToGroup(
                    groupId,
                    "Backlog:" +
                            "\nFrom: **" + begTime + "**" +
                            "\nTo: **" + endTime + "**",
                    threadId);*/

            seatalk.sendImgToGroup(groupId, result, threadId);

        } catch (TimeoutException e) {
            log.error("[ERROR] Execution timed out (exceeded 10 minutes threshold). Skipping current cycle.");

        } catch (ExecutionException e) {
            Throwable rootCause = e.getCause() != null ? e.getCause() : e;
            log.error("[ERROR] Task execution failed: {}", rootCause.getMessage());
            rootCause.printStackTrace();

        } catch (InterruptedException e) {
            log.warn("[WARN] Worker thread execution was interrupted during synchronization wait.");
            Thread.currentThread().interrupt();

        } catch (Exception e) {
            log.error("[CRITICAL ERROR] Unhandled exception occurred in current cycle: {}", e.getMessage());
            e.printStackTrace();
        }
    }

    private static void executeRePrintCommand(String cmd, String groupId, String threadId) {
        // reprint --lmtracking='SPXVN062524848687'

        try {
            Matcher matcher = ARG_PATTERN.matcher(cmd);
            String lmTrackingNo = null;

            while (matcher.find()) {
                String key = matcher.group("key");
                String value = matcher.group("value");
                if (key.equalsIgnoreCase("lmtracking")) {
                    lmTrackingNo = value;
                }
            }

            if (lmTrackingNo == null) {
                seatalk.sendMsgToGroup(groupId, "Please input LM Tracking Number!", threadId);
                return;
            }

            seatalk.sendMsgToGroup(groupId, "Im thinking ...\nPlease wait a second ...", threadId);

            String result = ApiCalling.getRePrintOrderAsString(lmTrackingNo);

            seatalk.sendMsgToGroup(
                    groupId,
                    "Re-print Order in same task:" +
                            "\nLM Tracking: **" + lmTrackingNo.toUpperCase() + "**" +
                            "\n\n" + result,
                    threadId);

        } catch (Exception e) {
            log.error("[CRITICAL ERROR] Unhandled exception occurred in current cycle: {}", e.getMessage());
            e.printStackTrace();
        }
    }

    private static void executeAskAICommand(String cmd, String groupId, String threadId) {
        // ask-ai <prompt>

        String prompt = cmd.substring("ask-ai".length()).trim();

        if (prompt.isEmpty()) {
            seatalk.sendMsgToGroup(groupId, "Prompt is empty!", threadId);
            return;
        }

        prompt = prompt + " (câu trả lời không vượt quá 4096 ký tự)";

        seatalk.sendMsgToGroup(groupId, "Im thinking ...\nPlease wait a second ...", threadId);

        GeminiService.getInstance().askGemini(prompt)
                .thenAccept(aiResponse -> {
                    seatalk.sendMsgToGroup(groupId, aiResponse, threadId);
                })
                .exceptionally(ex -> {
                    seatalk.sendMsgToGroup(groupId, "[ERROR]: " + ex.getMessage(), threadId);
                    return null;
                });
    }

    private static void executeSwitchAutoModeCommand(String cmd, String groupId, String threadId) {
        // switch-mode --mode='1' --from='2026/07/19 18:00:00' --to='2026/07/20 18:00:00'

        try {
            Matcher matcher = ARG_PATTERN.matcher(cmd);

            String mode = "";
            String begTime = "";
            String endTime = "";

            while (matcher.find()) {
                String key = matcher.group("key");
                String value = matcher.group("value");

                switch (key.toLowerCase()) {
                    case "mode":
                        mode = value;
                        break;
                    case "from":
                        begTime = value;
                        break;
                    case "to":
                        endTime = value;
                        break;
                }
            }

            if (mode.equals("1") || mode.equals("2")) {
                AutoModeConfig.saveProperties(mode, "", "");
                seatalk.sendMsgToGroup(groupId, "Successfully switching to mode: " + mode, threadId);

            } else if (mode.equals("3")) {
                try {
                    LocalDateTime begLdt = LocalDateTime.parse(begTime, DATE_TIME_FORMATTER);
                    LocalDateTime endLdt = LocalDateTime.parse(endTime, DATE_TIME_FORMATTER);

                    if (endLdt.isBefore(begLdt)) {
                        seatalk.sendMsgToGroup(
                                groupId,
                                "END_TIME (" + endTime + ") < BEG_TIME (" + begTime + "). Please try again!",
                                threadId);
                    } else {
                        AutoModeConfig.saveProperties(mode, begTime, endTime);
                        seatalk.sendMsgToGroup(
                                groupId,
                                "Successfully switching to mode: " + mode + "\nFrom: " + begTime + "\nTo: " + endTime,
                                threadId);
                    }

                } catch (DateTimeParseException e) {
                    seatalk.sendMsgToGroup(groupId, "Wrong format date/time!", threadId);
                }

            } else {
                seatalk.sendMsgToGroup(groupId, "Invalid mode!", threadId);
            }

        } catch (Exception e) {
            log.error("[CRITICAL ERROR] Unhandled exception occurred in current cycle: {}", e.getMessage());
            e.printStackTrace();
        }
    }
}