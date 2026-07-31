package app;

import seatalk.SeaTalkBotWebSocketClient;

import java.io.InputStream;
import java.util.Properties;

public class SeaTalkBotCheck {

    private static final String CONFIG_FILE_PATH = "creds/amon.properties";

    public static void main(String[] args) {
        Properties props = new Properties();
        try (InputStream input = SeaTalkBotCheck.class.getClassLoader().getResourceAsStream(CONFIG_FILE_PATH)) {
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

        SeaTalkBotWebSocketClient client = new SeaTalkBotWebSocketClient(
                appId,
                appSecret,
                (eventType, eventData) -> {
                    System.out.println("\n[EVENT RECEIVED] Type: " + eventType);
                    System.out.println("Data detail: " + eventData);

                    if ("message_from_bot_subscriber".equals(eventType)) {
                        System.out.println("-> Received message from user!");
                    } else if ("new_mentioned_message_received_from_group_chat".equals(eventType)) {
                        System.out.println("-> Bot tagged in group chat!");
                    }
                }
        );

        try {
            client.connect();
            System.out.println(">>> Activated command connect(), waiting for Handshake and Ping/Pong...\n");

            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            System.out.println("END.");
            client.close();
        } catch (Exception e) {
            System.err.println("Error while connecting: " + e.getMessage());
            e.printStackTrace();
        }
    }

}