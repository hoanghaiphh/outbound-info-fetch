package excel;

import com.alibaba.excel.EasyExcel;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

import static app.Constants.*;

public class ExcelHelper {

    private static final List<String> STATUS_LIST = Arrays.asList(
            "Created",
            "Pending Pick",
            "Picking",
            "Picked",
            "Pick Fail",
            "Checking",
            "Checked",
            "Packing",
            "Packed",
            "Shipping",
            "Outbound",
            "Cancel"
    );

    private static Map<String, Integer> getStatusCounts(String warehouse) {
        Map<String, Integer> statusCounts = new HashMap<>();
        for (String status : STATUS_LIST) {
            statusCounts.put(status, 0);
        }

        Set<String> processedColB = new HashSet<>();
        Path path = Paths.get(OUTPUT_DIR + File.separator + warehouse);

        if (!Files.exists(path) || !Files.isDirectory(path)) {
            System.err.printf("Reports directory for %s does not exist!", warehouse);
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
                            System.err.printf("File reading error: %s \n %s", file.getFileName(), e.getMessage());
                        }
                    });
        } catch (Exception e) {
            System.err.printf("Failed to read %s Reports directory! \n %s", warehouse, e.getMessage());
        }

        return statusCounts;
    }

    public static void displayResultsInComparison() {

        Map<String, Integer> countsVNDB = getStatusCounts("VNDB");
        Map<String, Integer> countsVNDL = getStatusCounts("VNDL");

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

}