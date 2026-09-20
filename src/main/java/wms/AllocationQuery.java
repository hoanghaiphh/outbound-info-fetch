package wms;

import io.restassured.response.Response;
import common.config.ApiConfig;
import common.config.CookiesConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static common.constants.GlobalConstants.*;

public class AllocationQuery {

    private static final String SEARCH_STAFF_ALLOCATION_ENDPOINT = "/api/v2/apps/dashboard/labor/dsstaff/search_staff_allocation";

    private static List<Map<String, String>> searchStaffAllocation(Map<String, String> cookies) {

        List<Map<String, String>> result = new ArrayList<>();
        int pageNo = 1;
        int count = 100;
        int total = 0;

        do {
            Map<String, Object> queryParams = new HashMap<>();
            queryParams.put("activity_operation", 5);
            queryParams.put("pageno", pageNo);
            queryParams.put("count", count);

            Response response = ApiConfig.executeGET(cookies, WMS_URL, queryParams, SEARCH_STAFF_ALLOCATION_ENDPOINT);

            if (pageNo == 1) {
                Integer totalObj = response.jsonPath().get("data.total");
                total = (totalObj != null) ? totalObj : 0;
            }

            List<Map<String, Object>> rawList = response.jsonPath().getList("data.list");

            if (rawList == null || rawList.isEmpty()) {
                break;
            }

            for (Map<String, Object> item : rawList) {
                Map<String, String> entry = new HashMap<>();
                entry.put("staff_email", item.get("staff_email") != null ? item.get("staff_email").toString() : null);
                entry.put("act_name", item.get("act_name") != null ? item.get("act_name").toString() : null);
                result.add(entry);
            }

            if (result.size() >= total) {
                break;
            }

            pageNo++;

        } while (true);

        return result;
    }

    public static int[] countPickerPacker(String warehouse) {
        Map<String, String> cookies = CookiesConfig.loadCookies(DEFAULT_USER, warehouse);
        List<Map<String, String>> staffAllocation = searchStaffAllocation(cookies);

        int picker = 0;
        int packer = 0;

        for (Map<String, String> staff : staffAllocation) {
            String actName = staff.get("act_name");

            if ("Picking".equalsIgnoreCase(actName)) {
                picker++;
            } else if ("CheckingWhilePacking".equalsIgnoreCase(actName)) {
                packer++;
            }
        }

        return new int[]{picker, packer};
    }

}
