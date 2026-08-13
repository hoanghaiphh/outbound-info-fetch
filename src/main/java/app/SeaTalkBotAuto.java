package app;

import excel.ExcelHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import wms.CookiesConfig;
import seatalk.SeaTalkService;
import wms.ApiCalling;
import seatalk.ReportImgGenerator;
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

    private static void mainRun() {
        try {
            String[] timeRange = CommonHelper.getWorkingTimeRange(false); // todo: ???
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

            String result = ReportImgGenerator.createReportImage(OUTPUT_DIR);

            seatalk.sendMsgToGroup(BACKUP_GROUP_ID, "From: **" + begTime + "**\nTo: **" + endTime + "**");
            seatalk.sendImgToGroup(BACKUP_GROUP_ID, result);

            currentOrders = ExcelHelper.getOrders(OUTPUT_DIR);
            if (previousOrders != null) {
                int pickVNDB = currentOrders[0] - previousOrders[0];
                int pickVNDL = currentOrders[1] - previousOrders[1];
                int packVNDB = currentOrders[2] - previousOrders[2];
                int packVNDL = currentOrders[3] - previousOrders[3];
                seatalk.sendMsgToGroup(BACKUP_GROUP_ID, "Approximate speed:" +
                        "\nPicking:" +
                        "\n- VNDB: **" + pickVNDB + "** orders / 5 mins" +
                        "\n- VNDL: **" + pickVNDL + "** orders / 5 mins" +
                        "\nPacking:" +
                        "\n- VNDB: **" + packVNDB + "** orders / 5 mins" +
                        "\n- VNDL: **" + packVNDL + "** orders / 5 mins");
            }
            previousOrders = currentOrders;

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