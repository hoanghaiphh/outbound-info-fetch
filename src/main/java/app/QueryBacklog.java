package app;

import excel.ExcelHelper;
import wms.ApiCalling;
import general.CommonHelper;
import wms.CookiesConfig;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.*;

import static general.GlobalConstants.*;

public class QueryBacklog {

    private static String username, password, begTime, endTime;

    private static final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("WMS-Backlog");
        return t;
    });

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
                System.out.println("BEGIN_TIME: " + DATE_TIME_PATTERN);
                begTime = scanner.nextLine().trim();

                if (begTime.isBlank()) {
                    String[] timeRange = CommonHelper.getWorkingTimeRange(true);
                    begTime = timeRange[0];
                    endTime = timeRange[1];
                    break;
                }

                try {
                    LocalDateTime begLdt = LocalDateTime.parse(begTime, DATE_TIME_FORMATTER);

                    System.out.println("END_TIME: " + DATE_TIME_PATTERN);
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

            System.out.println("Fetching data...");
            mainRun();

        } catch (Exception e) {
            System.err.println("[CRITICAL] Application crashed during execution initialization.");
            e.printStackTrace();
        } finally {
            executor.shutdownNow();
        }
    }

    private static void mainRun() {
        try {
            if (!CookiesConfig.isCookiesValid(username, "VNDB")
                    || !CookiesConfig.isCookiesValid(username, "VNDL")) {
                CookiesConfig.loginAndSaveCookies(username, password);
            }

            Map<String, String> cookiesB = CookiesConfig.loadCookies(username, "VNDB");
            Map<String, String> cookiesL = CookiesConfig.loadCookies(username, "VNDL");

            CommonHelper.cleanUpDirectory(OUTPUT_DIR);

            String curTime = LocalDateTime.now().format(DATE_TIME_FORMATTER);

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

            ExcelHelper.displayResultsInComparison();

            System.out.println("From: " + ANSI_YELLOW + begTime + ANSI_RESET
                    + " - To: " + ANSI_YELLOW + endTime + ANSI_RESET);
            System.out.println("Generated time: " + ANSI_YELLOW + curTime + ANSI_RESET
                    + " By: " + ANSI_YELLOW + username + ANSI_RESET);

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
