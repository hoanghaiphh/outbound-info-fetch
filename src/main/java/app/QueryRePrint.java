package app;

import general.CommonHelper;
import wms.ApiCalling;
import wms.CookiesConfig;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.*;

import static general.GlobalConstants.*;
import static general.GlobalConstants.DATE_TIME_FORMATTER;
import static general.GlobalConstants.DATE_TIME_PATTERN;

public class QueryRePrint {

    private static String username, password, warehouse, begTime, endTime, lmTrackingNo;

    private static Map<String, String> cookies;

    public static void main(String[] args) {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.println("USERNAME:");
                username = scanner.nextLine().trim();

                if (CREDENTIALS.containsKey(username)) {
                    password = CREDENTIALS.get(username);
                    break;
                } else {
                    System.err.println("Invalid username. Please try again!");
                }
            }

            while (true) {
                System.out.println("WAREHOUSE:");
                warehouse = scanner.nextLine().trim();

                if (warehouse.equalsIgnoreCase("B") || warehouse.equalsIgnoreCase("L")) {
                    break;
                } else {
                    System.err.println("Invalid warehouse. Please try again!");
                }
            }

            while (true) {
                System.out.println("BEGIN_TIME <" + DATE_TIME_PATTERN + ">:");
                begTime = scanner.nextLine().trim();

                if (begTime.isBlank()) {
                    String[] timeRange = CommonHelper.getWorkingTimeRange(false);
                    begTime = timeRange[0];
                    endTime = timeRange[1];
                    break;
                }

                try {
                    LocalDateTime begLdt = LocalDateTime.parse(begTime, DATE_TIME_FORMATTER);

                    System.out.println("END_TIME <" + DATE_TIME_PATTERN + ">:");
                    endTime = scanner.nextLine().trim();

                    LocalDateTime endLdt = LocalDateTime.parse(endTime, DATE_TIME_FORMATTER);

                    if (endLdt.isAfter(begLdt)) {
                        break;
                    } else {
                        System.err.printf("END_TIME (%s) < BEG_TIME (%s). Please try again!\n", endTime, begTime);
                    }

                } catch (DateTimeParseException e) {
                    System.err.println("Wrong format. Please try again!");
                }
            }

            System.out.println("LM TRACKING NO:");
            lmTrackingNo = scanner.nextLine().trim();

            System.out.println("Searching data...");
            mainRun();

        } catch (Exception e) {
            System.err.println("[CRITICAL] Application crashed during execution initialization.");
            e.printStackTrace();
        }
    }

    private static void mainRun() {
        try {
            if (!CookiesConfig.isCookiesValid(username, "VNDB")
                    || !CookiesConfig.isCookiesValid(username, "VNDL")) {
                CookiesConfig.loginAndSaveCookies(username, password);
            }

            if (warehouse.equalsIgnoreCase("B")) {
                cookies = CookiesConfig.loadCookies(username, "VNDB");
            } else if (warehouse.equalsIgnoreCase("L")) {
                cookies = CookiesConfig.loadCookies(username, "VNDL");
            }

            ApiCalling.displayRePrintOrderFromLMTrackingNo(cookies, begTime, endTime, lmTrackingNo);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}