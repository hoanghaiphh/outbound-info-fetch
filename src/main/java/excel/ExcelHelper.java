package excel;

import com.alibaba.excel.EasyExcel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

import static common.constants.GlobalConstants.*;

public class ExcelHelper {

    private static final Logger log = LogManager.getLogger(ExcelHelper.class);

    public static Map<String, Integer> getStatusCounts(String warehouse, String parentDir, String target3pl) {
        Map<String, Integer> statusCounts = new HashMap<>();
        for (String status : STATUS_LIST) {
            statusCounts.put(status, 0);
        }

        Set<String> processedColB = new HashSet<>();
        Path path = Paths.get(parentDir + File.separator + warehouse);

        if (!Files.exists(path) || !Files.isDirectory(path)) {
            log.error("Reports directory for {} does not exist!", warehouse);
            return statusCounts;
        }

        try (Stream<Path> paths = Files.list(path)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".xlsx"))
                    .forEach(file -> {
                        try {
                            EasyExcel.read(file.toFile(), RowData.class,
                                            new ExcelDataListener(statusCounts, processedColB, target3pl))
                                    .sheet(0)
                                    .headRowNumber(1)
                                    .doRead();
                        } catch (Exception e) {
                            log.error("File reading error: {} \n {}", file.getFileName(), e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.error("Failed to read {} Reports directory! \n {}", warehouse, e.getMessage());
        }

        return statusCounts;
    }

    public static int[] getActiveStatusCounts(Map<String, Integer> statusCounts) {
        int total = 0, picked = 0, packed = 0;

        for (String status : STATUS_LIST) {
            int value = statusCounts.getOrDefault(status, 0);

            if (!"Cancel".equals(status)) total += value;
            if (isPickedCounts(status)) picked += value;
            if (isPackedCounts(status)) packed += value;
        }

        return new int[]{total, picked, packed};
    }

    private static boolean isPickedCounts(String status) {
        return status.equals("Picked") || status.equals("Pick Fail")
                || status.equals("Checking") || status.equals("Checked")
                || status.equals("Packing") || status.equals("Packed")
                || status.equals("Shipping") || status.equals("Outbound");
    }

    private static boolean isPackedCounts(String status) {
        return status.equals("Packed") || status.equals("Shipping") || status.equals("Outbound");
    }

}