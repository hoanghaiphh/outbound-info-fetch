package wms;

import io.restassured.response.Response;
import common.config.ApiConfig;
import common.config.CookiesConfig;

import java.time.Instant;
import java.util.*;

import static common.constants.GlobalConstants.*;

public class RePrintQuery {

    private static final String SEARCH_ORDER_ENDPOINT = "/api/v2/apps/process/outbound/salesorder/search_order";
    private static final String GET_ORDER_DETAIL_ENDPOINT = "/api/v2/apps/process/outbound/salesorder/get_order_detail";
    private static final String SEARCH_CHECKING_TASK_ENDPOINT = "/api/v2/apps/process/taskcenter/checkingtask/search_checking_task";
    private static final String GET_CHECKING_TASK_DETAIL_ENDPOINT = "/api/v2/apps/process/taskcenter/checkingtask/get_checking_task_detail";

    public record RePrintOrderInfo(String orderNumber, int printCount, List<PrintLog> printLogs) {

        public record PrintLog(String timeFormatted, String operator) {
        }
    }

    private static List<String> getOrderListFromLMTrackingNo(Map<String, String> cookies, String lmTrackingNo) {
        Map<String, Object> queryParams1 = new HashMap<>();
        queryParams1.put("count", 20);
        queryParams1.put("is_get_total", 0);
        queryParams1.put("pageno", 1);
        queryParams1.put("second_search_key", lmTrackingNo);

        Response searchOrderResponse = ApiConfig.executeGET(cookies, WMS_URL, queryParams1, SEARCH_ORDER_ENDPOINT);

        List<Map<String, Object>> searchList = searchOrderResponse.jsonPath().getList("data.list");
        if (searchList == null || searchList.isEmpty()) {
            return Collections.emptyList();
        }

        String orderNumber = searchOrderResponse.jsonPath().getString("data.list[0].order_number");

        Map<String, Object> queryParams2 = new HashMap<>();
        queryParams2.put("order_number", orderNumber);

        Response getTaskIdResponse = ApiConfig.executeGET(cookies, WMS_URL, queryParams2, GET_ORDER_DETAIL_ENDPOINT);

        String taskId = getTaskIdResponse.jsonPath().getString("data.pickup_id");
        if (taskId == null) {
            return Collections.emptyList();
        }

        Map<String, Object> queryParams3 = new HashMap<>();
        queryParams3.put("is_get_total", 1);
        queryParams3.put("search_key", taskId);
        queryParams3.put("pageno", 1);
        queryParams3.put("count", 200);

        Response searchCheckingTaskResponse = ApiConfig.executeGET(cookies, WMS_URL, queryParams3, SEARCH_CHECKING_TASK_ENDPOINT);

        List<String> taskNumbers = searchCheckingTaskResponse.jsonPath().getList("data.list.task_number", String.class);
        return taskNumbers != null ? taskNumbers : Collections.emptyList();
    }

    private static List<RePrintOrderInfo> getRePrintOrders(String lmTrackingNo) {
        Map<String, String> cookies = null;
        List<String> orderList = Collections.emptyList();

        for (String warehouse : List.of("VNDB", "VNDL")) {
            cookies = CookiesConfig.loadCookies(DEFAULT_USER, warehouse);
            orderList = getOrderListFromLMTrackingNo(cookies, lmTrackingNo);
            if (!orderList.isEmpty()) break;
        }

        if (orderList.isEmpty()) return null;

        List<RePrintOrderInfo> result = new ArrayList<>();

        for (String task : orderList) {
            Map<String, Object> queryParams = new HashMap<>();
            queryParams.put("task_number", task);

            Response response = ApiConfig.executeGET(cookies, WMS_URL, queryParams, GET_CHECKING_TASK_DETAIL_ENDPOINT);

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

    public static String getRePrintOrderAsString(String lmTrackingNo) {

        List<RePrintOrderInfo> orders = getRePrintOrders(lmTrackingNo);

        if (orders == null) {
            return "Invalid LM Tracking Number.";
        } else if (orders.isEmpty()) {
            return "No Re-print Order in same task.";
        }

        StringBuilder result = new StringBuilder();

        for (RePrintOrderInfo order : orders) {
            result.append("LM Tracking: **").append(order.orderNumber()).append("**")
                    .append(" (Print count: **").append(order.printCount()).append("**)\n");
            result.append("Print log:\n");

            if (!order.printLogs().isEmpty()) {
                for (RePrintOrderInfo.PrintLog log : order.printLogs()) {
                    result.append(String.format("   - %s | %s\n", log.timeFormatted(), log.operator()));
                }
            } else {
                result.append("   - No log available\n");
            }
            result.append("\n");
        }

        return result.toString();
    }
}