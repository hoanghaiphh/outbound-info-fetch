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

import static general.GlobalConstants.*;

public class ExcelHelper {

    private static final Logger log = LogManager.getLogger(ExcelHelper.class);

    public static Map<String, Integer> getStatusCounts(String warehouse, String parentDir) {
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
                            EasyExcel.read(file.toFile(), RowData.class, new ExcelDataListener(statusCounts, processedColB))
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

    public static void displayResultsInComparison(String parentDir) {
        Map<String, Integer> countsVNDB = getStatusCounts("VNDB", parentDir);
        Map<String, Integer> countsVNDL = getStatusCounts("VNDL", parentDir);

        // %-20s: left alignment & width 20 chars - %15s: right alignment & width 15 chars
        System.out.println("\n---------------------------------------------");
        System.out.printf("| %-15s | %10s | %-10s |\n", "STATUS", "VNDB", "VNDL");
        System.out.println("---------------------------------------------");

        int totalVNDB = 0;
        int totalVNDL = 0;

        for (String status : STATUS_LIST) {
            int valVNDB = countsVNDB.getOrDefault(status, 0);
            int valVNDL = countsVNDL.getOrDefault(status, 0);

            if (!status.equals("Cancel")) {
                totalVNDB += valVNDB;
                totalVNDL += valVNDL;
            }

            String coloredStatus;
            if (status.equals("Created") || status.equals("Picked") || status.equals("Packed") || status.equals("Outbound")) {
                coloredStatus = ANSI_CYAN + status + ANSI_RESET;
            } else {
                coloredStatus = status;
            }
            String formattedVNDB = ANSI_RED + String.format("%10d", valVNDB) + ANSI_RESET;
            String formattedVNDL = ANSI_GREEN + String.format("%-10d", valVNDL) + ANSI_RESET;

            int widthOffset = coloredStatus.contains(ANSI_CYAN) ? 15 + 9 : 15;
            System.out.printf("| %-" + widthOffset + "s | %s | %s |\n",
                    coloredStatus, formattedVNDB, formattedVNDL);
            // System.out.printf("| %-15s | %10d | %-10d |\n", status, valVNDB, valVNDL);
        }

        System.out.println("---------------------------------------------");
        System.out.printf("| %-15s | %10d | %-10d |\n", "TOTAL ex.Cancel", totalVNDB, totalVNDL);
        System.out.println("---------------------------------------------");
    }

    public static int[] getOrders(String parentDir) {
        Map<String, Integer> countsVNDB = getStatusCounts("VNDB", parentDir);
        Map<String, Integer> countsVNDL = getStatusCounts("VNDL", parentDir);

        int pickedVNDB = 0;
        int pickedVNDL = 0;
        int packedVNDB = 0;
        int packedVNDL = 0;

        for (String status : Arrays
                .asList("Picked", "Pick Fail", "Checking", "Checked", "Packing", "Packed", "Shipping", "Outbound")) {
            int valVNDB = countsVNDB.getOrDefault(status, 0);
            int valVNDL = countsVNDL.getOrDefault(status, 0);

            pickedVNDB += valVNDB;
            pickedVNDL += valVNDL;

            if (status.equals("Packed") || status.equals("Shipping") || status.equals("Outbound")) {
                packedVNDB += valVNDB;
                packedVNDL += valVNDL;
            }
        }

        return new int[]{pickedVNDB, pickedVNDL, packedVNDB, packedVNDL};
    }

}