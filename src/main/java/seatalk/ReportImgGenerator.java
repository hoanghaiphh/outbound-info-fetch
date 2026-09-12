package seatalk;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;

import static general.GlobalConstants.*;
import static excel.ExcelHelper.getStatusCounts;

public class ReportImgGenerator {

    private static final int IMAGE_WIDTH = 730;
    private static final int ROW_HEIGHT = 30;
    private static final int PADDING = 40;

    private static final int COL_STATUS_X = PADDING;
    private static final int COL_VNDB_SPX_RIGHT_X = 300;
    private static final int COL_VNDB_GHN_RIGHT_X = 420;
    private static final int COL_VNDL_SPX_RIGHT_X = 570;
    private static final int COL_VNDL_GHN_RIGHT_X = 690;

    private static final Color BG_COLOR = new Color(30, 30, 30);
    private static final Color LINE_COLOR = new Color(70, 70, 70);
    private static final Color COLOR_VNDB = new Color(255, 99, 71); // Red
    private static final Color COLOR_VNDL = new Color(50, 205, 50); // Green
    private static final Color COLOR_ACTIVE_STATUS = new Color(0, 191, 255);
    private static final Color COLOR_NORMAL_STATUS = new Color(200, 200, 200);

    public static String createReportImage(String parentDir, String begTime, String endTime, int... speedList) {
        Map<String, Integer> countsVNDB_SPX = getStatusCounts("VNDB", parentDir, "SPX Express");
        Map<String, Integer> countsVNDB_GHN = getStatusCounts("VNDB", parentDir, "GHN - Hàng Cồng Kềnh");
        Map<String, Integer> countsVNDL_SPX = getStatusCounts("VNDL", parentDir, "SPX Express");
        Map<String, Integer> countsVNDL_GHN = getStatusCounts("VNDL", parentDir, "GHN - Hàng Cồng Kềnh");

        // 1. Tính toán kích thước ảnh trước
        int imageHeight = PADDING * 2 + 50 + (STATUS_LIST.size() * ROW_HEIGHT) + (ROW_HEIGHT * 3) + 30;

        BufferedImage bufferedImage = new BufferedImage(IMAGE_WIDTH, imageHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = bufferedImage.createGraphics();

        try {
            // 2. Setup cấu hình chung
            setupGraphics(g2d, imageHeight);
            FontMetrics metrics = g2d.getFontMetrics();

            int currentY = PADDING + 20;

            // 3. Vẽ Tiêu đề (4 cột dữ liệu mới)
            drawHeader(g2d, metrics, currentY);
            currentY = drawHorizontalLine(g2d, currentY + 12);

            // 4. Vẽ Dữ liệu & Tính tổng tích hợp cho 4 cột
            int[] totals = drawDataRows(g2d, metrics, currentY, countsVNDB_SPX, countsVNDB_GHN, countsVNDL_SPX, countsVNDL_GHN, speedList);
            currentY += (STATUS_LIST.size() * ROW_HEIGHT);

            // 5. Vẽ Phần tổng số & % packed+
            currentY = drawHorizontalLine(g2d, currentY + 15);
            drawFooter(g2d, metrics, currentY + 25, totals[0], totals[1], totals[2], totals[3]);
            drawPercentageFooter(g2d, metrics, currentY + 25 + ROW_HEIGHT, totals);

            // 6. Vẽ 2 dòng thông tin thời gian ở dưới cùng
            currentY = currentY + 25 + ROW_HEIGHT + 60;
            g2d.setColor(Color.LIGHT_GRAY);
            g2d.drawString("From:  " + begTime + "  → To:  " + endTime, COL_STATUS_X, currentY);

            currentY += 25;
            String currentTimeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));
            g2d.drawString("Generated time:  " + currentTimeStr, COL_STATUS_X, currentY);

            g2d.dispose();
            return convertToBase64(bufferedImage);

        } catch (Exception e) {
            g2d.dispose();
            throw new RuntimeException("Failed to create report!", e);
        }
    }

    private static void setupGraphics(Graphics2D g2d, int imageHeight) {
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g2d.setColor(BG_COLOR);
        g2d.fillRect(0, 0, IMAGE_WIDTH, imageHeight);

        try {
            InputStream fontStream = ReportImgGenerator.class.getResourceAsStream("/fonts/FiraCode-Medium.ttf");
            Font jetbrainsFont = Font.createFont(Font.TRUETYPE_FONT, fontStream).deriveFont(15f);
            g2d.setFont(jetbrainsFont);
        } catch (Exception e) {
            g2d.setFont(new Font("Monospaced", Font.PLAIN, 15));
        }
    }

    private static void drawHeader(Graphics2D g2d, FontMetrics metrics, int y) {
        g2d.setColor(Color.WHITE);
        g2d.drawString("STATUS", COL_STATUS_X, y);
        g2d.drawString("VNDB-SPX", COL_VNDB_SPX_RIGHT_X - metrics.stringWidth("VNDB-SPX"), y);
        g2d.drawString("VNDB-GHN", COL_VNDB_GHN_RIGHT_X - metrics.stringWidth("VNDB-GHN"), y);
        g2d.drawString("VNDL-SPX", COL_VNDL_SPX_RIGHT_X - metrics.stringWidth("VNDL-SPX"), y);
        g2d.drawString("VNDL-GHN", COL_VNDL_GHN_RIGHT_X - metrics.stringWidth("VNDL-GHN"), y);
    }

    private static int drawHorizontalLine(Graphics2D g2d, int y) {
        g2d.setColor(LINE_COLOR);
        g2d.drawLine(PADDING, y, IMAGE_WIDTH - PADDING, y);
        return y;
    }

    private static int[] drawDataRows(Graphics2D g2d, FontMetrics metrics, int startY,
                                      Map<String, Integer> countsVNDB_SPX, Map<String, Integer> countsVNDB_GHN,
                                      Map<String, Integer> countsVNDL_SPX, Map<String, Integer> countsVNDL_GHN,
                                      int... speedList) {
        int totalVNDB_SPX = 0, packedB_SPX = 0;
        int totalVNDB_GHN = 0, packedB_GHN = 0;
        int totalVNDL_SPX = 0, packedL_SPX = 0;
        int totalVNDL_GHN = 0, packedL_GHN = 0;
        int localY = startY;

        for (String status : STATUS_LIST) {
            localY += ROW_HEIGHT;

            int valB_SPX = countsVNDB_SPX.getOrDefault(status, 0);
            int valB_GHN = countsVNDB_GHN.getOrDefault(status, 0);
            int valL_SPX = countsVNDL_SPX.getOrDefault(status, 0);
            int valL_GHN = countsVNDL_GHN.getOrDefault(status, 0);

            if (!"Cancel".equals(status)) {
                totalVNDB_SPX += valB_SPX;
                totalVNDB_GHN += valB_GHN;
                totalVNDL_SPX += valL_SPX;
                totalVNDL_GHN += valL_GHN;
            }

            if (status.equals("Packed") || status.equals("Shipping") || status.equals("Outbound")) {
                packedB_SPX += valB_SPX;
                packedB_GHN += valB_GHN;
                packedL_SPX += valL_SPX;
                packedL_GHN += valL_GHN;
            }

            // 1. Vẽ cột Status
            g2d.setColor(HIGHLIGHT_STATUSES.contains(status) ? COLOR_ACTIVE_STATUS : COLOR_NORMAL_STATUS);
            g2d.drawString(status, COL_STATUS_X, localY);

            String speedB_SPX = "", speedB_GHN = "", speedL_SPX = "", speedL_GHN = "";

            // Xử lý 12 phần tử speedList cho 4 cột (mỗi cột 3 trạng thái: Created, Picked, Packed)
            if (speedList != null && speedList.length >= 12) {
                if ("Created".equals(status)) {
                    speedB_SPX = (speedList[0] == 0) ? "" : "(+" + speedList[0] + ") ";
                    speedB_GHN = (speedList[3] == 0) ? "" : "(+" + speedList[3] + ") ";
                    speedL_SPX = (speedList[6] == 0) ? "" : "(+" + speedList[6] + ") ";
                    speedL_GHN = (speedList[9] == 0) ? "" : "(+" + speedList[9] + ") ";
                } else if ("Picked".equals(status)) {
                    speedB_SPX = (speedList[1] == 0) ? "" : "(+" + speedList[1] + ") ";
                    speedB_GHN = (speedList[4] == 0) ? "" : "(+" + speedList[4] + ") ";
                    speedL_SPX = (speedList[7] == 0) ? "" : "(+" + speedList[7] + ") ";
                    speedL_GHN = (speedList[10] == 0) ? "" : "(+" + speedList[10] + ") ";
                } else if ("Packed".equals(status)) {
                    speedB_SPX = (speedList[2] == 0) ? "" : "(+" + speedList[2] + ") ";
                    speedB_GHN = (speedList[5] == 0) ? "" : "(+" + speedList[5] + ") ";
                    speedL_SPX = (speedList[8] == 0) ? "" : "(+" + speedList[8] + ") ";
                    speedL_GHN = (speedList[11] == 0) ? "" : "(+" + speedList[11] + ") ";
                }
            }

            // 2. Vẽ 4 cột dữ liệu (VNDB dùng COLOR_VNDB, VNDL dùng COLOR_VNDL)
            drawCell(g2d, metrics, valB_SPX, speedB_SPX, COL_VNDB_SPX_RIGHT_X, localY, COLOR_VNDB);
            drawCell(g2d, metrics, valB_GHN, speedB_GHN, COL_VNDB_GHN_RIGHT_X, localY, COLOR_VNDB);
            drawCell(g2d, metrics, valL_SPX, speedL_SPX, COL_VNDL_SPX_RIGHT_X, localY, COLOR_VNDL);
            drawCell(g2d, metrics, valL_GHN, speedL_GHN, COL_VNDL_GHN_RIGHT_X, localY, COLOR_VNDL);
        }

        return new int[]{
                totalVNDB_SPX, totalVNDB_GHN, totalVNDL_SPX, totalVNDL_GHN,
                packedB_SPX, packedB_GHN, packedL_SPX, packedL_GHN
        };
    }

    private static void drawCell(Graphics2D g2d, FontMetrics metrics, int value, String speed, int rightX, int y, Color mainColor) {
        String strVal = String.format("%,d", value);
        int valWidth = metrics.stringWidth(strVal);
        int numX = rightX - valWidth;

        // Vẽ con số chính
        g2d.setColor(mainColor);
        g2d.drawString(strVal, numX, y);

        // Vẽ speed màu vàng đặt phía trước con số
        if (!speed.isEmpty()) {
            g2d.setColor(Color.YELLOW);
            int speedWidth = metrics.stringWidth(speed);
            g2d.drawString(speed, numX - speedWidth, y);
        }
    }

    private static void drawFooter(Graphics2D g2d, FontMetrics metrics, int y, int t1, int t2, int t3, int t4) {
        g2d.setColor(Color.YELLOW);
        g2d.drawString("TOTAL ex.Cancel", COL_STATUS_X, y);

        drawFooterVal(g2d, metrics, t1, COL_VNDB_SPX_RIGHT_X, y);
        drawFooterVal(g2d, metrics, t2, COL_VNDB_GHN_RIGHT_X, y);
        drawFooterVal(g2d, metrics, t3, COL_VNDL_SPX_RIGHT_X, y);
        drawFooterVal(g2d, metrics, t4, COL_VNDL_GHN_RIGHT_X, y);
    }

    private static void drawFooterVal(Graphics2D g2d, FontMetrics metrics, int value, int rightX, int y) {
        String strVal = String.format("%,d", value);
        g2d.setColor(Color.YELLOW);
        g2d.drawString(strVal, rightX - metrics.stringWidth(strVal), y);
    }

    private static void drawPercentageFooter(Graphics2D g2d, FontMetrics metrics, int y, int[] data) {
        g2d.setColor(Color.ORANGE);
        g2d.drawString("% packed+", COL_STATUS_X, y);

        drawPercentageVal(g2d, metrics, data[4], data[0], COL_VNDB_SPX_RIGHT_X, y);
        drawPercentageVal(g2d, metrics, data[5], data[1], COL_VNDB_GHN_RIGHT_X, y);
        drawPercentageVal(g2d, metrics, data[6], data[2], COL_VNDL_SPX_RIGHT_X, y);
        drawPercentageVal(g2d, metrics, data[7], data[3], COL_VNDL_GHN_RIGHT_X, y);
    }

    private static void drawPercentageVal(Graphics2D g2d, FontMetrics metrics, int packed, int total, int rightX, int y) {
        double percent = (total == 0) ? 0.0 : ((double) packed / total) * 100.0;
        String strVal = String.format("%.2f%%", percent);
        g2d.setColor(Color.ORANGE);
        g2d.drawString(strVal, rightX - metrics.stringWidth(strVal), y);
    }

    private static String convertToBase64(BufferedImage image) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        }
    }

}
