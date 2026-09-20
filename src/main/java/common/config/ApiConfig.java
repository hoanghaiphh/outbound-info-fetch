package common.config;

import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.function.Supplier;

public class ApiConfig {

    private static final Logger log = LogManager.getLogger(ApiConfig.class);

    private static final RestAssuredConfig REST_ASSURED_CONFIG = RestAssuredConfig.config()
            .httpClient(HttpClientConfig.httpClientConfig()
                    .setParam("http.connection.timeout", 10000)
                    .setParam("http.socket.timeout", 30000));

    private static RequestSpecification requestSpecification(Map<String, String> cookies, String baseUrl) {
        return RestAssured.given()
                .config(REST_ASSURED_CONFIG)
                .baseUri(baseUrl)
                .cookies(cookies != null ? cookies : Map.of());
    }

    private static Response executeWithRetry(Supplier<Response> requestSupplier) {
        int maxRetries = 3;
        int retryCount = 0;

        while (true) {
            Response response = requestSupplier.get();
            if (response.getStatusCode() == 429 && retryCount < maxRetries) {
                retryCount++;
                log.warn("Received 429 (Too Many Requests). Retrying (Attempt {}/{})...",
                        retryCount, maxRetries);
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry wait process was interrupted", e);
                }
            } else {
                return response;
            }
        }
    }

    private static void verifyResponse(Response response) {
        int statusCode = response.getStatusCode();
        if (statusCode != 200) {
            throw new RuntimeException("API error. Status code: " + statusCode);
        }

        Integer retCode = response.jsonPath().get("retcode");
        if (retCode == null) {
            throw new RuntimeException("API error or missing retcode!");
        }

        String message = response.jsonPath().getString("message");
        if (retCode != 0) {
            throw new RuntimeException("API error. Ret code: " + retCode + ", Message: " + message);
        }
    }

    public static void executePOST(Map<String, String> cookies, String baseUrl,
                                   Map<String, Object> requestBody, String endpoint) {
        log.info("POST to: {}", endpoint);

        Response response = executeWithRetry(() -> {
            RequestSpecification spec = requestSpecification(cookies, baseUrl)
                    .contentType("application/json;charset=UTF-8");

            if (requestBody != null) {
                spec.body(requestBody);
            }

            return spec.post(endpoint);
        });

        verifyResponse(response);
    }

    public static Response executeGET(Map<String, String> cookies, String baseUrl,
                                      Map<String, Object> queryParams, String endpoint) {
        log.info("GET from: {}", endpoint);

        Response response = executeWithRetry(
                () -> requestSpecification(cookies, baseUrl)
                        .queryParams(queryParams != null ? queryParams : Map.of())
                        .get(endpoint)
        );

        verifyResponse(response);

        return response;
    }

    public static Response executeDownload(Map<String, String> cookies, String baseUrl,
                                           Map<String, Object> queryParams, String endpoint) {
        log.info("Downloading file from: {}", endpoint);

        Response response = executeWithRetry(
                () -> requestSpecification(cookies, baseUrl)
                        .queryParams(queryParams != null ? queryParams : Map.of())
                        .get(endpoint)
        );

        int statusCode = response.getStatusCode();
        if (statusCode != 200) {
            throw new RuntimeException("Download failed with status code: " + statusCode);
        }

        log.info("--> File downloaded successfully.");
        return response;
    }
}