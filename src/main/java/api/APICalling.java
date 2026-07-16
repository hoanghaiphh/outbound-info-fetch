package services;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class APICalling {

    private static final String BASE_URL = "https://wms.ssc.shopee.vn";

    private static final String OUTPUT_DIR = System.getProperty("user.dir") + File.separator + "output";

    private static RequestSpecification requestSpec(Map<String, String> cookies) {
        return RestAssured.given().baseUri(BASE_URL).cookies(cookies);
    }

    public static void generateReport(String begTime, String endTime, Map<String, String> cookies) {
        System.out.println("Generating report...");

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
        long begTimeEpoch = LocalDateTime.parse(begTime, formatter).toEpochSecond(ZoneOffset.ofHours(7));
        long endTimeEpoch = LocalDateTime.parse(endTime, formatter).toEpochSecond(ZoneOffset.ofHours(7));

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
            ObjectMapper mapper = new ObjectMapper();
            extraDataString = mapper.writeValueAsString(extraDataMap);
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
                    throw new RuntimeException("Failed to get Download URL. API status code "
                            + response.getStatusCode());
                }

                List<?> taskList = response.jsonPath().getList("data.list");
                if (taskList == null || taskList.isEmpty()) {
                    System.out.println("Report list is empty! Retrieving in 5 seconds...");
                    Thread.sleep(5000);
                    maxAttempts--;
                    continue;
                }

                String taskId = response.jsonPath().getString("data.list[0].task_id");
                Integer taskStatus = response.jsonPath().get("data.list[0].task_status");
                Integer progress = response.jsonPath().get("data.list[0].processed_percentage");

                System.out.println("[" + taskId + "] - Status: " + taskStatus + " - Progress: " + progress + "%");

                if (taskStatus != null && progress != null && taskStatus == 2 && progress == 100) {
                    return response.jsonPath().getString("data.list[0].download_link");
                }

                Thread.sleep(5000);
                maxAttempts--;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Thread was interrupted while waiting for the report to be generated!", e);
        }

        throw new RuntimeException("Timeout waiting for the report file to be generated!");
    }

    public static void downloadReportFile(Map<String, String> cookies, String subDir) {
        System.out.println("Preparing download URL...");
        String downloadUrl = getDownloadUrl(cookies);

        System.out.println("Downloading report...");
        Response response = requestSpec(cookies).get(downloadUrl);

        if (response.getStatusCode() != 200) {
            throw new RuntimeException("Failed to download report file. API status code "
                    + response.getStatusCode());
        }

        File targetDir = new File(subDir);
        if (!targetDir.isAbsolute()) {
            targetDir = new File(OUTPUT_DIR, subDir);
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

                    System.out.println(" -> Extracting part: [" + entry.getName() + "] to " + extractedFile.getAbsolutePath());
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
                throw new RuntimeException("Failed to unzip and extract multi-part report files!", e);
            }
        } else {
            String shopeeFileName = null;
            String contentDisposition = response.header("Content-Disposition");

            if (contentDisposition != null && contentDisposition.contains("filename=")) {
                int index = contentDisposition.indexOf("filename=");
                shopeeFileName = contentDisposition.substring(index + 9).trim().replace("\"", "");
                if (shopeeFileName.contains(";")) {
                    shopeeFileName = shopeeFileName.substring(0, shopeeFileName.indexOf(";")).trim();
                }
            }

            // fallback if Header is empty
            if (shopeeFileName == null || shopeeFileName.isEmpty()) {
                try {
                    String path = new java.net.URL(downloadUrl).getPath();
                    shopeeFileName = path.substring(path.lastIndexOf('/') + 1);
                } catch (Exception e) {
                    shopeeFileName = "report_" + System.currentTimeMillis() + ".xlsx";
                }
            }

            File finalSingleFile = new File(targetDir, shopeeFileName);
            System.out.println(" -> Saving file: [" + shopeeFileName + "] to " + finalSingleFile.getAbsolutePath());

            try (FileOutputStream fos = new FileOutputStream(finalSingleFile)) {
                fos.write(fileBytes);
            } catch (Exception e) {
                throw new RuntimeException("Error writing single file to disk: " + finalSingleFile.getAbsolutePath(), e);
            }
        }
    }

}