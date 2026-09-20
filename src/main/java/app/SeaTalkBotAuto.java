package app;

import excel.ExcelHelper;
import common.config.AutoModeConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import wms.AllocationQuery;
import wms.BacklogQuery;
import seatalk.SeaTalkService;
import common.utils.ReportImgGenerator;
import common.utils.CommonHelper;

import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static common.constants.GlobalConstants.*;

public class SeaTalkBotAuto {

    private static final Logger log = LogManager.getLogger(SeaTalkBotAuto.class);

    private static int[] previousVNDB_SPX, previousVNDB_GHN, previousVNDL_SPX, previousVNDL_GHN;

    private static final SeaTalkService seatalk = new SeaTalkService();

    private static final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("SeatalkBot-Worker");
        return t;
    });

    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Application shutting down, releasing executor resources...");
            executor.shutdownNow();
        }));

        try {
            while (!Thread.currentThread().isInterrupted()) {
                mainRun();
                TimeUnit.MINUTES.sleep(5);
            }
        } catch (InterruptedException e) {
            log.info("Main loop interrupted. Stopping bot...");
            Thread.currentThread().interrupt();

        } finally {
            if (!executor.isShutdown()) {
                executor.shutdownNow();
            }
        }
    }

    private static String[] setTimeRange() {
        try {
            String mode = AutoModeConfig.getProperty("mode");
            if (mode == null) {
                AutoModeConfig.saveProperties("2", "", "");
                return CommonHelper.getWorkingTimeRange(false);
            }

            return switch (mode) {
                case "1" -> CommonHelper.getWorkingTimeRange(true);
                case "2" -> CommonHelper.getWorkingTimeRange(false);
                case "3" -> {
                    String begTime = AutoModeConfig.getProperty("from");
                    String endTime = AutoModeConfig.getProperty("to");
                    yield new String[]{begTime, endTime};
                }
                default -> CommonHelper.getWorkingTimeRange(false);
            };
        } catch (Exception e) {
            log.error("Error occurred while setting time range, falling back to default: {}", e.getMessage(), e);
            AutoModeConfig.saveProperties("2", "", "");
            return CommonHelper.getWorkingTimeRange(false);
        }
    }

    private static int[] calculateSpeed(int[] current, int[] previous) {
        int total = Math.max((current[0] - previous[0]), 0);
        int picked = Math.max((current[1] - previous[1]), 0);
        int packed = Math.max((current[2] - previous[2]), 0);
        return new int[]{total, picked, packed};
    }

    private static void mainRun() {
        try {
            String[] timeRange = setTimeRange();
            String begTime = timeRange[0];
            String endTime = timeRange[1];

            CommonHelper.cleanUpDirectory(OUTPUT_DIR);

            CompletableFuture<Void> taskB = CompletableFuture.runAsync(() -> {
                try {
                    BacklogQuery.saveBacklogToLocal("VNDB", begTime, endTime, OUTPUT_DIR);
                } catch (Exception e) {
                    throw new CompletionException("Failed to fetch VNDB report data!", e);
                }
            }, executor);

            CompletableFuture<Void> taskL = CompletableFuture.runAsync(() -> {
                try {
                    BacklogQuery.saveBacklogToLocal("VNDL", begTime, endTime, OUTPUT_DIR);
                } catch (Exception e) {
                    throw new CompletionException("Failed to fetch VNDL report data!", e);
                }
            }, executor);

            CompletableFuture.allOf(taskB, taskL).get(10, TimeUnit.MINUTES);

            Map<String, Integer> statusVNDB_SPX
                    = ExcelHelper.getStatusCounts("VNDB", OUTPUT_DIR, "SPX Express");
            int[] currentVNDB_SPX = ExcelHelper.getActiveStatusCounts(statusVNDB_SPX);
            int[] speedVNDB_SPX = new int[]{0, 0, 0};

            Map<String, Integer> statusVNDB_GHN
                    = ExcelHelper.getStatusCounts("VNDB", OUTPUT_DIR, "GHN - Hàng Cồng Kềnh");
            int[] currentVNDB_GHN = ExcelHelper.getActiveStatusCounts(statusVNDB_GHN);
            int[] speedVNDB_GHN = new int[]{0, 0, 0};

            Map<String, Integer> statusVNDL_SPX
                    = ExcelHelper.getStatusCounts("VNDL", OUTPUT_DIR, "SPX Express");
            int[] currentVNDL_SPX = ExcelHelper.getActiveStatusCounts(statusVNDL_SPX);
            int[] speedVNDL_SPX = new int[]{0, 0, 0};

            Map<String, Integer> statusVNDL_GHN
                    = ExcelHelper.getStatusCounts("VNDL", OUTPUT_DIR, "GHN - Hàng Cồng Kềnh");
            int[] currentVNDL_GHN = ExcelHelper.getActiveStatusCounts(statusVNDL_GHN);
            int[] speedVNDL_GHN = new int[]{0, 0, 0};

            if (previousVNDB_SPX != null && previousVNDB_GHN != null
                    && previousVNDL_SPX != null && previousVNDL_GHN != null) {

                speedVNDB_SPX = calculateSpeed(currentVNDB_SPX, previousVNDB_SPX);
                speedVNDB_GHN = calculateSpeed(currentVNDB_GHN, previousVNDB_GHN);
                speedVNDL_SPX = calculateSpeed(currentVNDL_SPX, previousVNDL_SPX);
                speedVNDL_GHN = calculateSpeed(currentVNDL_GHN, previousVNDL_GHN);
            }

            previousVNDB_SPX = currentVNDB_SPX;
            previousVNDB_GHN = currentVNDB_GHN;
            previousVNDL_SPX = currentVNDL_SPX;
            previousVNDL_GHN = currentVNDL_GHN;

            int[] staffB = AllocationQuery.countPickerPacker("VNDB");
            int[] staffL = AllocationQuery.countPickerPacker("VNDL");

            int[] extraInfo = Stream.of(speedVNDB_SPX, speedVNDB_GHN, speedVNDL_SPX, speedVNDL_GHN, staffB, staffL)
                    .flatMapToInt(IntStream::of)
                    .toArray();

            String result = ReportImgGenerator.createReportImage(
                    statusVNDB_SPX, statusVNDB_GHN, statusVNDL_SPX, statusVNDL_GHN, begTime, endTime, extraInfo);

            seatalk.sendImgToGroup(BACKUP_GROUP_ID, result);

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

}