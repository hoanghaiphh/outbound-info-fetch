package app;

import excel.ExcelHelper;
import api.APICalling;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.*;
import java.util.stream.Stream;

import static app.Constants.*;
import static api.CookiesConfig.*;

public class MainApp {

    private static String username, password, begTime, endTime;
    private static Map<String, String> cookiesB, cookiesL;

    private static final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    public static void main(String[] args) {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("USERNAME:\n");
                username = scanner.nextLine().trim();

                if (CREDENTIALS.containsKey(username)) {
                    password = CREDENTIALS.get(username);
                    prepareCookies();
                    break;
                } else {
                    System.err.println("Invalid username. Please try again!");
                }
            }

            while (true) {
                System.out.println("BEGIN_TIME: " + DATE_TIME_PATTERN);
                begTime = scanner.nextLine().trim();

                if (begTime.isBlank()) {
                    begTime = getWorkingTimeRange()[0];
                    endTime = getWorkingTimeRange()[1];
                    break;
                }

                try {
                    LocalDateTime begLdt = LocalDateTime.parse(begTime, FORMATTER);

                    System.out.println("END_TIME: " + DATE_TIME_PATTERN);
                    endTime = scanner.nextLine().trim();

                    LocalDateTime endLdt = LocalDateTime.parse(endTime, FORMATTER);

                    if (endLdt.isAfter(begLdt)) {
                        break;
                    } else {
                        System.err.printf("END_TIME (%s) < BEG_TIME (%s). Please try again!\n", endTime, begTime);
                    }

                } catch (DateTimeParseException e) {
                    System.err.println("Wrong format. Please try again!");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            shutdownExecutor();
            return;
        }

        System.out.println("Fetching data...");
        fetchInfo();

        try {
            while (!Thread.currentThread().isInterrupted()) {
                TimeUnit.MINUTES.sleep(3);
                prepareCookies();
                fetchInfo();
            }
        } catch (InterruptedException e) {
            System.err.println("Main thread interrupted. Shutting down...");
            Thread.currentThread().interrupt();
        } finally {
            shutdownExecutor();
        }
    }

    private static void prepareCookies() {
        if (!isCookiesValid(username, "VNDB") || !isCookiesValid(username, "VNDL")) {
            loginAndSaveCookies(username, password);
        }
        cookiesB = loadCookies(username, "VNDB");
        cookiesL = loadCookies(username, "VNDL");
    }

    private static void fetchInfo() {
        cleanUpOutput();

        String curTime = LocalDateTime.now().format(FORMATTER);

        if (executor.isShutdown()) {
            System.err.println("Executor pool has been shutdown unexpectedly!");
            return;
        }

        CompletableFuture<Void> taskB = CompletableFuture.runAsync(() -> {
            try {
                APICalling.generateReport(begTime, endTime, cookiesB);
                APICalling.downloadReportFile(cookiesB, "VNDB");
            } catch (Exception e) {
                throw new CompletionException("VNDB failed", e);
            }
        }, executor);

        CompletableFuture<Void> taskL = CompletableFuture.runAsync(() -> {
            try {
                APICalling.generateReport(begTime, endTime, cookiesL);
                APICalling.downloadReportFile(cookiesL, "VNDL");
            } catch (Exception e) {
                throw new CompletionException("VNDL failed", e);
            }
        }, executor);

        CompletableFuture<Void> combinedTask = CompletableFuture.allOf(taskB, taskL);

        try {
            combinedTask.get(10, TimeUnit.MINUTES);

            ExcelHelper.displayResultsInComparison();

            System.out.println("From: " + ANSI_YELLOW + begTime + ANSI_RESET
                    + " - To: " + ANSI_YELLOW + endTime + ANSI_RESET);
            System.out.println("Generated time: " + ANSI_YELLOW + curTime + ANSI_RESET
                    + " By: " + ANSI_YELLOW + username + ANSI_RESET);

        } catch (TimeoutException e) {
            System.err.println("Timeout reached! One or more tasks exceeded 10 minutes limit. Canceling...");
            taskB.cancel(true);
            taskL.cancel(true);
        } catch (ExecutionException e) {
            System.err.println("Task Execution failed: " + e.getCause().getMessage());
            e.printStackTrace();
        } catch (InterruptedException e) {
            System.err.println("Execution was interrupted!");
            Thread.currentThread().interrupt();
        }
    }

    private static void cleanUpOutput() {
        Path path = Paths.get(OUTPUT_DIR);
        if (!Files.exists(path)) return;

        try (Stream<Path> walk = Files.walk(path)) {
            walk.filter(p -> !p.equals(path))
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (Exception e) {
                            System.err.println("Failed to delete: " + p + " | " + e.getMessage());
                        }
                    });
        } catch (Exception e) {
            System.err.println("Failed to clean up directory! " + e.getMessage());
        }
    }

    private static String[] getWorkingTimeRange() {
        LocalDateTime now = LocalDateTime.now();
        LocalTime boundaryTime = LocalTime.of(18, 0, 0);

        LocalDateTime startTime;
        LocalDateTime endTime;

        if (now.toLocalTime().isBefore(boundaryTime)) {
            startTime = now.minusDays(1).with(boundaryTime);
            endTime = now.with(boundaryTime);
        } else {
            startTime = now.with(boundaryTime);
            endTime = now.plusDays(1).with(boundaryTime);
        }

        String startStr = startTime.format(FORMATTER);
        String endStr = endTime.format(FORMATTER);

        return new String[]{startStr, endStr};
    }

    private static void shutdownExecutor() {
        if (!executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

}
