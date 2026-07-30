package app;

import general.CommonHelper;
import seatalk.SeaTalkBotWebSocketClient;
import seatalk.SeaTalkService;
import wms.ApiCalling;
import wms.CookiesConfig;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static general.GlobalConstants.*;

public class SeaTalkBotTest3 {

    private static final String CONFIG_FILE_PATH = "creds/amon.properties";
    private static final SeaTalkService seatalk = new SeaTalkService();

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
                SeaTalkBotTest3::handleIncomingEvent
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

        if ("new_mentioned_message_received_from_group_chat".equals(eventType)) {

            Map<String, Object> eventObj = (Map<String, Object>) eventData.get("event");
            if (eventObj == null) return;

//            Object seatalkIdObj = eventObj.get("seatalk_id");
//            String seatalkId = seatalkIdObj != null ? String.valueOf(seatalkIdObj) : null;
//            String email = (String) eventObj.get("email");

            Map<String, Object> messageObj = (Map<String, Object>) eventObj.get("message");
            if (messageObj == null) return;

            String tag = (String) messageObj.get("tag");

            if ("text".equals(tag)) {
                Map<String, Object> textObj = (Map<String, Object>) messageObj.get("text");
                String content = textObj != null ? ((String) textObj.get("plain_text")).trim() : "";

//                System.out.printf("[New message] From %s (%s): %s%n", email, seatalkId, content);
                executeCommand("seatalkId", content);
            }
        }
    }

    private static void executeCommand(String seatalkId, String content) {
        String reformatContent = content.toLowerCase().trim();

        if (seatalkId != null && reformatContent.contains("reprint")) {
            executeRePrintCommand(reformatContent);
        }

    }

    private static void executeRePrintCommand(String cmd) {
        // reprint --from='2026/07/19 18:00:00' --to='2026/07/20 18:00:00' --warehouse='L' --lmtracking='SPXVN062524848687'
        try {

            Pattern pattern = Pattern.compile("--(?<key>\\w+)='(?<value>[^']*)'");
            Matcher matcher = pattern.matcher(cmd);

            String begTime = null;
            String endTime = null;
            String warehouse = null;
            String lmTrackingNo = null;

            while (matcher.find()) {
                String key = matcher.group("key");
                String value = matcher.group("value");

                switch (key) {
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
                String [] timeRange = CommonHelper.getWorkingTimeRange(false); // todo
                begTime = timeRange[0];
                endTime = timeRange[1];
            }

            if (warehouse == null) {
                seatalk.sendMsgToGroup(AMON_GROUP_ID, "Please input Warehouse!");
                return;
            }

            if (lmTrackingNo == null) {
                seatalk.sendMsgToGroup(AMON_GROUP_ID, "Please input LM Tracking Number!");
                return;
            }

            if (!CookiesConfig.isCookiesValid(DEFAULT_USER, "VNDB")
                    || !CookiesConfig.isCookiesValid(DEFAULT_USER, "VNDL")) {
                CookiesConfig.loginAndSaveCookies(DEFAULT_USER, DEFAULT_PW);
            }

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

            seatalk.sendMsgToGroup(AMON_GROUP_ID,
                    "Re-print packing task:\n" +
                            "From: " + begTime + " - To: " + endTime + "\n" +
                            "LM Tracking No: " + lmTrackingNo.toUpperCase());
            seatalk.sendMsgToGroup(AMON_GROUP_ID, result);

        } catch (Exception e) {
            System.err.println("[CRITICAL ERROR] Unhandled exception occurred in current cycle: " + e.getMessage());
            e.printStackTrace();
        }
    }
}