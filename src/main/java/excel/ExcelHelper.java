package excel;

import com.alibaba.excel.EasyExcel;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.stream.Stream;

import static app.GlobalConstants.*;

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

    public static String displayResultsInComparison() {

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

        File imgFile = createReportImage(countsVNDB, countsVNDL, totalVNDB, totalVNDL);
        if (imgFile != null && imgFile.exists()) {
            try {
                byte[] fileContent = Files.readAllBytes(imgFile.toPath());
                String base64Result = Base64.getEncoder().encodeToString(fileContent);

                imgFile.delete();

                return base64Result;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return null;
    }

    private static File createReportImage(Map<String, Integer> countsVNDB, Map<String, Integer> countsVNDL,
                                          int totalVNDB, int totalVNDL) {
        try {
            int rowHeight = 30;
            int padding = 40;
            int totalRows = STATUS_LIST.size() + 3;
            int imageWidth = 500;
            int imageHeight = (totalRows * rowHeight) + (padding * 2) - 50;

            BufferedImage bufferedImage = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = bufferedImage.createGraphics();

            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Background & Frame
            g2d.setColor(new Color(30, 30, 30));
            g2d.fillRect(0, 0, imageWidth, imageHeight);

            Font font = new Font("Monospaced", Font.PLAIN, 16);
            FontMetrics metrics = g2d.getFontMetrics(font);

            // Set X position for columns
            int colStatusX = padding;
            int colVndbRightX = 320;
            int colVndlRightX = 460;

            int currentY = padding;

            // Title row
            g2d.setFont(font);
            g2d.setColor(Color.WHITE);

            g2d.drawString("STATUS", colStatusX, currentY);
            g2d.drawString("VNDB", colVndbRightX - metrics.stringWidth("VNDB"), currentY);
            g2d.drawString("VNDL", colVndlRightX - metrics.stringWidth("VNDL"), currentY);

            g2d.setColor(new Color(70, 70, 70));
            currentY += 10;
            g2d.drawLine(padding, currentY, imageWidth - padding, currentY);
            currentY += rowHeight;

            // Data rows
            for (String status : STATUS_LIST) {
                int valVNDB = countsVNDB.getOrDefault(status, 0);
                int valVNDL = countsVNDL.getOrDefault(status, 0);

                // Status column
                if (status.equals("Created") || status.equals("Picked") || status.equals("Packed") || status.equals("Outbound")) {
                    g2d.setFont(font);
                    g2d.setColor(new Color(0, 191, 255));
                } else {
                    g2d.setFont(font);
                    g2d.setColor(new Color(200, 200, 200));
                }
                g2d.drawString(status, colStatusX, currentY);

                // VNDB column
                g2d.setFont(font);
                g2d.setColor(new Color(255, 99, 71));
                String strVndb = String.format("%,d", valVNDB);
                int strVndbWidth = metrics.stringWidth(strVndb);
                g2d.drawString(strVndb, colVndbRightX - strVndbWidth, currentY);

                // VNDL column
                g2d.setColor(new Color(50, 205, 50));
                String strVndl = String.format("%,d", valVNDL);
                int strVndlWidth = metrics.stringWidth(strVndl);
                g2d.drawString(strVndl, colVndlRightX - strVndlWidth, currentY);

                currentY += rowHeight;
            }

            // TOTAL row
            g2d.setColor(new Color(70, 70, 70));
            g2d.drawLine(padding, currentY - rowHeight + 10, imageWidth - padding, currentY - rowHeight + 10);
            currentY += 10;

            g2d.setFont(font);
            g2d.setColor(Color.YELLOW);

            g2d.drawString("TOTAL ex.Cancel", colStatusX, currentY);

            String strTotalVndb = String.format("%,d", totalVNDB);
            g2d.drawString(strTotalVndb, colVndbRightX - metrics.stringWidth(strTotalVndb), currentY);

            String strTotalVndl = String.format("%,d", totalVNDL);
            g2d.drawString(strTotalVndl, colVndlRightX - metrics.stringWidth(strTotalVndl), currentY);

            g2d.dispose();

            File outputFile = new File("report.png");
            ImageIO.write(bufferedImage, "png", outputFile);
            return outputFile;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

}