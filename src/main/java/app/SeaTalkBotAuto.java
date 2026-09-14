package app;

import excel.ExcelHelper;
import general.AutoModeConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import wms.CookiesConfig;
import seatalk.SeaTalkService;
import wms.ApiCalling;
import general.ReportImgGenerator;
import general.CommonHelper;

import java.util.Map;
import java.util.concurrent.*;

import static general.GlobalConstants.*;

public class SeaTalkBotAuto {

    private static final Logger log = LogManager.getLogger(SeaTalkBotAuto.class);

    private static int[] previousOrders, currentOrders;

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

    private static void mainRun() {
        try {
            String[] timeRange = setTimeRange();
            String begTime = timeRange[0];
            String endTime = timeRange[1];

            Map<String, String> cookiesB = CookiesConfig.loadCookies(DEFAULT_USER, "VNDB");
            Map<String, String> cookiesL = CookiesConfig.loadCookies(DEFAULT_USER, "VNDL");

            CommonHelper.cleanUpDirectory(OUTPUT_DIR);

            CompletableFuture<Void> taskB = CompletableFuture.runAsync(() -> {
                try {
                    ApiCalling.generateReportFile(begTime, endTime, cookiesB);
                    ApiCalling.downloadReportFile(cookiesB, "VNDB", OUTPUT_DIR);
                } catch (Exception e) {
                    throw new CompletionException("Failed to fetch VNDB report data!", e);
                }
            }, executor);

            CompletableFuture<Void> taskL = CompletableFuture.runAsync(() -> {
                try {
                    ApiCalling.generateReportFile(begTime, endTime, cookiesL);
                    ApiCalling.downloadReportFile(cookiesL, "VNDL", OUTPUT_DIR);
                } catch (Exception e) {
                    throw new CompletionException("Failed to fetch VNDL report data!", e);
                }
            }, executor);

            CompletableFuture.allOf(taskB, taskL).get(10, TimeUnit.MINUTES);

            int ttVNDB_SPX = 0, pickVNDB_SPX = 0, packVNDB_SPX = 0;
            int ttVNDB_GHN = 0, pickVNDB_GHN = 0, packVNDB_GHN = 0;
            int ttVNDL_SPX = 0, pickVNDL_SPX = 0, packVNDL_SPX = 0;
            int ttVNDL_GHN = 0, pickVNDL_GHN = 0, packVNDL_GHN = 0;

            currentOrders = ExcelHelper.getOrders(OUTPUT_DIR);

            if (previousOrders != null) {
                ttVNDB_SPX = Math.max((currentOrders[0] - previousOrders[0]), 0);
                pickVNDB_SPX = Math.max((currentOrders[1] - previousOrders[1]), 0);
                packVNDB_SPX = Math.max((currentOrders[2] - previousOrders[2]), 0);

                ttVNDB_GHN = Math.max((currentOrders[3] - previousOrders[3]), 0);
                pickVNDB_GHN = Math.max((currentOrders[4] - previousOrders[4]), 0);
                packVNDB_GHN = Math.max((currentOrders[5] - previousOrders[5]), 0);

                ttVNDL_SPX = Math.max((currentOrders[6] - previousOrders[6]), 0);
                pickVNDL_SPX = Math.max((currentOrders[7] - previousOrders[7]), 0);
                packVNDL_SPX = Math.max((currentOrders[8] - previousOrders[8]), 0);

                ttVNDL_GHN = Math.max((currentOrders[9] - previousOrders[9]), 0);
                pickVNDL_GHN = Math.max((currentOrders[10] - previousOrders[10]), 0);
                packVNDL_GHN = Math.max((currentOrders[11] - previousOrders[11]), 0);
            }

            previousOrders = currentOrders;

            int[] staffB = ApiCalling.countPickerPacker("VNDB");
            int[] staffL = ApiCalling.countPickerPacker("VNDL");

            String result = ReportImgGenerator.createReportImage(OUTPUT_DIR, begTime, endTime,
                    ttVNDB_SPX, pickVNDB_SPX, packVNDB_SPX,
                    ttVNDB_GHN, pickVNDB_GHN, packVNDB_GHN,
                    ttVNDL_SPX, pickVNDL_SPX, packVNDL_SPX,
                    ttVNDL_GHN, pickVNDL_GHN, packVNDL_GHN,
                    staffB[0], staffB[1], staffL[0], staffL[1]);

            // seatalk.sendMsgToGroup(BACKUP_GROUP_ID, "From: **" + begTime + "**\n→ To: **" + endTime + "**");
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