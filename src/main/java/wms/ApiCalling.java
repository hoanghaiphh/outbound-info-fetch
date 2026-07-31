package wms;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import seatalk.RePrintOrderInfo;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static general.GlobalConstants.*;

public class ApiCalling {

    private static final String BASE_URL = "https://wms.ssc.shopee.vn";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final RestAssuredConfig REST_ASSURED_CONFIG = RestAssuredConfig.config()
            .httpClient(HttpClientConfig.httpClientConfig()
                    .setParam("http.connection.timeout", 10000)
                    .setParam("http.socket.timeout", 30000));

    private static RequestSpecification requestSpec(Map<String, String> cookies) {
        return RestAssured.given()
                .config(REST_ASSURED_CONFIG)
                .baseUri(BASE_URL)
                .cookies(cookies);
    }

    public static void generateReportFile(String begTime, String endTime, Map<String, String> cookies) {

        long begTimeEpoch = LocalDateTime.parse(begTime, DATE_TIME_FORMATTER).toEpochSecond(ZoneOffset.ofHours(7));
        long endTimeEpoch = LocalDateTime.parse(endTime, DATE_TIME_FORMATTER).toEpochSecond(ZoneOffset.ofHours(7));

        Map<String, Object> extraDataMap = new HashMap<>();
        extraDataMap.put("beg_ctime", begTimeEpoch);
        extraDataMap.put("end_ctime", endTimeEpoch);
        extraDataMap.put("from_listpage", 1);
        extraDataMap.put("include_sku_list", 1);
        extraDataMap.put("order_type", 0);
        extraDataMap.put("date_ref", 0);
        extraDataMap.put("time_from", begTimeEpoch);
        extraDataMap.put("time_to", endTimeEpoch);

        String extraDataString;
        try {
            extraDataString = MAPPER.writeValueAsString(extraDataMap);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize extra_data map to JSON string", e);
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("export_module", 2);
        requestBody.put("task_type", 722);
        requestBody.put("extra_data", extraDataString);

        Response response = requestSpec(cookies)
                .contentType("application/json;charset=UTF-8")
                .body(requestBody)
                .post("/api/v2/apps/basic/reportcenter/create_export_task");

        if (response.getStatusCode() != 200) {
            throw new RuntimeException("Failed to generate report. API status code = " + response.getStatusCode());
        }
    }

    private static String getDownloadUrl(Map<String, String> cookies) {
        int maxAttempts = 20;

        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("page_no", 1);
        queryParams.put("count", 20);
        queryParams.put("export_module", 2);
        queryParams.put("task_type", 722);
        queryParams.put("is_myself", 1);

        try {
            while (maxAttempts > 0) {
                Response response = requestSpec(cookies)
                        .queryParams(queryParams)
                        .get("/api/v2/apps/basic/reportcenter/search_export_task");

                if (response.getStatusCode() != 200) {
                    throw new RuntimeException("Failed to get Download URL. API status code " + response.getStatusCode());
                }

                List<?> taskList = response.jsonPath().getList("data.list");
                if (taskList == null || taskList.isEmpty()) {
                    Thread.sleep(5000);
                    maxAttempts--;
                    continue;
                }

                Integer taskStatus = response.jsonPath().get("data.list[0].task_status");
                Integer progress = response.jsonPath().get("data.list[0].processed_percentage");

                if (taskStatus != null && progress != null && taskStatus == 2 && progress == 100) {
                    return response.jsonPath().getString("data.list[0].download_link");
                }

                Thread.sleep(5000);
                maxAttempts--;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Thread was interrupted while waiting for report generation!", e);
        }

        throw new RuntimeException("Timeout waiting for the report file to be generated!");
    }

    public static void downloadReportFile(Map<String, String> cookies, String subDir, String parentDir) {
        String downloadUrl = getDownloadUrl(cookies);
        Response response = requestSpec(cookies).get(downloadUrl);

        if (response.getStatusCode() != 200) {
            throw new RuntimeException("Failed to download report file. API status code " + response.getStatusCode());
        }

        File targetDir = new File(subDir);
        if (!targetDir.isAbsolute()) {
            targetDir = new File(parentDir, subDir);
        }

        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }

        byte[] fileBytes = response.asByteArray();
        String contentType = response.contentType();

        boolean isZipFile = (contentType != null && contentType.toLowerCase().contains("zip"))
                || downloadUrl.toLowerCase().contains(".zip");

        if (isZipFile) {
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(fileBytes))) {
                ZipEntry entry;

                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }

                    File extractedFile = new File(targetDir, entry.getName());
                    File entryParent = extractedFile.getParentFile();
                    if (entryParent != null && !entryParent.exists()) {
                        entryParent.mkdirs();
                    }

                    try (FileOutputStream fos = new FileOutputStream(extractedFile)) {
                        byte[] buffer = new byte[4096];
                        int length;
                        while ((length = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, length);
                        }
                    }
                    zis.closeEntry();
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to unzip and extract report files!", e);
            }
        } else {
            String shopeeFileName = parseFileNameFromHeader(response.header("Content-Disposition"));

            if (shopeeFileName == null || shopeeFileName.isEmpty()) {
                try {
                    String path = new java.net.URL(downloadUrl).getPath();
                    shopeeFileName = path.substring(path.lastIndexOf('/') + 1);
                } catch (Exception e) {
                    shopeeFileName = "report_" + System.currentTimeMillis() + ".xlsx";
                }
            }

            File finalSingleFile = new File(targetDir, shopeeFileName);

            try (FileOutputStream fos = new FileOutputStream(finalSingleFile)) {
                fos.write(fileBytes);
            } catch (Exception e) {
                throw new RuntimeException("Error writing file to disk: " + finalSingleFile.getAbsolutePath(), e);
            }
        }
    }

    private static String parseFileNameFromHeader(String contentDisposition) {
        if (contentDisposition == null) return null;
        if (contentDisposition.contains("filename=")) {
            int index = contentDisposition.indexOf("filename=");
            String fileName = contentDisposition.substring(index + 9).trim().replace("\"", "");
            if (fileName.contains(";")) {
                fileName = fileName.substring(0, fileName.indexOf(";")).trim();
            }
            return URLDecoder.decode(fileName, StandardCharsets.UTF_8);
        }
        return null;
    }

    private static List<String> getOrderListFromLMTrackingNo(
            Map<String, String> cookies, String begTime, String endTime, String lmTrackingNo) {

        long begTimeEpoch = LocalDateTime.parse(begTime, DATE_TIME_FORMATTER).toEpochSecond(ZoneOffset.ofHours(7));
        long endTimeEpoch = LocalDateTime.parse(endTime, DATE_TIME_FORMATTER).toEpochSecond(ZoneOffset.ofHours(7));

        Map<String, Object> queryParams1 = new HashMap<>();
        queryParams1.put("beg_ctime", begTimeEpoch);
        queryParams1.put("count", 20);
        queryParams1.put("end_ctime", endTimeEpoch);
        queryParams1.put("is_get_total", 0);
        queryParams1.put("pageno", 1);
        queryParams1.put("second_search_key", lmTrackingNo);

        Response searchOrderResponse = requestSpec(cookies)
                .queryParams(queryParams1)
                .get("/api/v2/apps/process/outbound/salesorder/search_order");

        if (searchOrderResponse.getStatusCode() != 200) {
            throw new RuntimeException("Failed to search Order. API status code " + searchOrderResponse.getStatusCode());
        }

        List<Map<String, Object>> searchList = searchOrderResponse.jsonPath().getList("data.list");
        if (searchList == null || searchList.isEmpty()) {
            return Collections.emptyList();
        }

        String orderNumber = searchOrderResponse.jsonPath().getString("data.list[0].order_number");

        Response getTaskIdResponse = requestSpec(cookies)
                .queryParam("order_number", orderNumber)
                .get("/api/v2/apps/process/outbound/salesorder/get_order_detail");

        if (getTaskIdResponse.getStatusCode() != 200) {
            throw new RuntimeException("Failed to get Order detail. API status code " + getTaskIdResponse.getStatusCode());
        }

        String taskId = getTaskIdResponse.jsonPath().getString("data.pickup_id");
        if (taskId == null) {
            return Collections.emptyList();
        }

        Map<String, Object> queryParams2 = new HashMap<>();
        queryParams2.put("is_get_total", 1);
        queryParams2.put("search_key", taskId);
        queryParams2.put("start_time", begTimeEpoch);
        queryParams2.put("end_time", endTimeEpoch);
        queryParams2.put("pageno", 1);
        queryParams2.put("count", 200);

        Response searchCheckingTaskResponse = requestSpec(cookies)
                .queryParams(queryParams2)
                .get("/api/v2/apps/process/taskcenter/checkingtask/search_checking_task");

        if (searchCheckingTaskResponse.getStatusCode() != 200) {
            throw new RuntimeException("Failed to search Checking Task. API status code " + searchCheckingTaskResponse.getStatusCode());
        }

        List<String> taskNumbers = searchCheckingTaskResponse.jsonPath().getList("data.list.task_number", String.class);
        return taskNumbers != null ? taskNumbers : Collections.emptyList();
    }

    private static Response getCheckingTaskDetail(Map<String, String> cookies, String checkingTaskId) {
        Response response = requestSpec(cookies)
                .queryParam("task_number", checkingTaskId)
                .get("/api/v2/apps/process/taskcenter/checkingtask/get_checking_task_detail");

        if (response.getStatusCode() != 200) {
            throw new RuntimeException("Failed to get Checking Task Detail. API status code " + response.getStatusCode());
        }

        return response;
    }

    private static List<RePrintOrderInfo> getRePrintOrders(
            Map<String, String> cookies, String begTime, String endTime, String lmTrackingNo) {

        List<String> orderList = getOrderListFromLMTrackingNo(cookies, begTime, endTime, lmTrackingNo);
        List<RePrintOrderInfo> result = new ArrayList<>();

        if (orderList.isEmpty()) return null;

        for (String task : orderList) {
            Response response = getCheckingTaskDetail(cookies, task);
            Integer printAwbCount = response.jsonPath().get("data.print_awb_count");

            if (printAwbCount != null && printAwbCount > 1) {
                String orderNumber = response.jsonPath().get("data.order_info.lm_tracking_no");
                List<Map<String, Object>> printLogsRaw = response.jsonPath().getList("data.print_awb_log_list");

                List<RePrintOrderInfo.PrintLog> printLogs = new ArrayList<>();
                if (printLogsRaw != null) {
                    for (Map<String, Object> log : printLogsRaw) {
                        Number ctimeNum = (Number) log.get("ctime");
                        String operator = (String) log.get("operator");

                        String timeFormatted = (ctimeNum != null)
                                ? TIME_FORMATTER.format(Instant.ofEpochSecond(ctimeNum.longValue()))
                                : "N/A";
                        printLogs.add(new RePrintOrderInfo.PrintLog(timeFormatted, operator));
                    }
                }

                result.add(new RePrintOrderInfo(orderNumber, printAwbCount, printLogs));
            }
        }

        return result;
    }

    public static void printToConsoleRePrintOrder(
            Map<String, String> cookies, String begTime, String endTime, String lmTrackingNo) {

        List<RePrintOrderInfo> orders = getRePrintOrders(cookies, begTime, endTime, lmTrackingNo);

        for (RePrintOrderInfo order : orders) {
            System.out.println(ANSI_YELLOW + "==================================================");
            System.out.printf("LM Tracking No: %s (Print count: %s)\n",
                    ANSI_CYAN + order.getOrderNumber() + ANSI_YELLOW,
                    ANSI_CYAN + order.getPrintCount() + ANSI_YELLOW);
            System.out.println("Print log:");

            if (!order.getPrintLogs().isEmpty()) {
                for (RePrintOrderInfo.PrintLog log : order.getPrintLogs()) {
                    System.out.printf("   - %s | Operator: %s\n", log.getTimeFormatted(), log.getOperator());
                }
            } else {
                System.out.println("   - No log available");
            }
            System.out.println();
        }
    }

    public static String getRePrintOrderAsString(
            Map<String, String> cookies, String begTime, String endTime, String lmTrackingNo) {

        List<RePrintOrderInfo> orders = getRePrintOrders(cookies, begTime, endTime, lmTrackingNo);

        if (orders == null) {
            return "Information invalid! Please check again.";
        } else if (orders.isEmpty()) {
            return "No Re-print Order in same task.";
        }

        StringBuilder result = new StringBuilder();

        for (RePrintOrderInfo order : orders) {
            result.append("LM Tracking No: ").append(order.getOrderNumber())
                    .append(" (Print count: ").append(order.getPrintCount()).append(")\n");
            result.append("Print log:\n");

            if (!order.getPrintLogs().isEmpty()) {
                for (RePrintOrderInfo.PrintLog log : order.getPrintLogs()) {
                    result.append(String.format("   - %s | Operator: %s\n", log.getTimeFormatted(), log.getOperator()));
                }
            } else {
                result.append("   - No log available\n");
            }
            result.append("\n");
        }

        return result.toString();
    }
}