package wms;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.response.Response;
import common.config.ApiConfig;
import common.config.CookiesConfig;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static common.constants.GlobalConstants.*;

public class BacklogQuery {

    private static final String CREATE_EXPORT_TASK_ENDPOINT = "/api/v2/apps/basic/reportcenter/create_export_task";
    private static final String SEARCH_EXPORT_TASK_ENDPOINT = "/api/v2/apps/basic/reportcenter/search_export_task";

    public static void saveBacklogToLocal(String warehouse, String begTime, String endTime, String parentDir) {

        Map<String, String> cookies = CookiesConfig.loadCookies(DEFAULT_USER, warehouse);
        generateReportFile(cookies, begTime, endTime);
        String downloadUrl = getDownloadUrl(cookies);
        downloadReportFile(cookies, parentDir, warehouse, downloadUrl);
    }

    private static void generateReportFile(Map<String, String> cookies, String begTime, String endTime) {

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
            extraDataString = new ObjectMapper().writeValueAsString(extraDataMap);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize extra_data map to JSON string", e);
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("export_module", 2);
        requestBody.put("task_type", 722);
        requestBody.put("extra_data", extraDataString);

        ApiConfig.executePOST(cookies, WMS_URL, requestBody, CREATE_EXPORT_TASK_ENDPOINT);
    }

    private static String getDownloadUrl(Map<String, String> cookies) {

        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("page_no", 1);
        queryParams.put("count", 20);
        queryParams.put("export_module", 2);
        queryParams.put("task_type", 722);
        queryParams.put("is_myself", 1);

        int maxAttempts = 30;
        try {
            while (maxAttempts > 0) {
                Response response = ApiConfig.executeGET(cookies, WMS_URL, queryParams, SEARCH_EXPORT_TASK_ENDPOINT);

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

    private static void downloadReportFile(Map<String, String> cookies, String parentDir, String subDir, String downloadUrl) {

        Response response = ApiConfig.executeDownload(cookies, WMS_URL, null, downloadUrl);

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

}
