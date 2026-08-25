package seatalk;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import java.io.InputStream;
import java.util.Base64;
import java.util.Map;

import static general.GlobalConstants.*;
import static excel.ExcelHelper.getStatusCounts;

public class ReportImgGenerator {

    private static final int IMAGE_WIDTH = 500;
    private static final int ROW_HEIGHT = 30;
    private static final int PADDING = 40;

    private static final int COL_STATUS_X = PADDING;
    private static final int COL_VNDB_RIGHT_X = 320;
    private static final int COL_VNDL_RIGHT_X = 460;

    private static final Color BG_COLOR = new Color(30, 30, 30);
    private static final Color LINE_COLOR = new Color(70, 70, 70);
    private static final Color COLOR_VNDB = new Color(255, 99, 71);
    private static final Color COLOR_VNDL = new Color(50, 205, 50);
    private static final Color COLOR_ACTIVE_STATUS = new Color(0, 191, 255);
    private static final Color COLOR_NORMAL_STATUS = new Color(200, 200, 200);

    public static String createReportImage(String parentDir, int... speedList) {
        Map<String, Integer> countsVNDB = getStatusCounts("VNDB", parentDir);
        Map<String, Integer> countsVNDL = getStatusCounts("VNDL", parentDir);

        // 1. Tính toán kích thước ảnh trước
        int imageHeight = PADDING * 2 + 50 + (STATUS_LIST.size() * ROW_HEIGHT) + 30;

        BufferedImage bufferedImage = new BufferedImage(IMAGE_WIDTH, imageHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = bufferedImage.createGraphics();

        try {
            // 2. Setup cấu hình chung
            setupGraphics(g2d, imageHeight);
            FontMetrics metrics = g2d.getFontMetrics();

            int currentY = PADDING + 20;

            // 3. Vẽ Tiêu đề
            drawHeader(g2d, metrics, currentY);
            currentY = drawHorizontalLine(g2d, currentY + 12);

            // 4. Vẽ Dữ liệu & Tính tổng tích hợp
            int[] totals = drawDataRows(g2d, metrics, currentY, countsVNDB, countsVNDL, speedList);
            currentY += (STATUS_LIST.size() * ROW_HEIGHT); // Cập nhật Y sau khi vẽ xong toàn bộ các hàng data

            // 5. Vẽ Phần tổng số
            currentY = drawHorizontalLine(g2d, currentY + 15);
            drawFooter(g2d, metrics, currentY + 25, totals[0], totals[1]);

            g2d.dispose();

            // 6. Chuyển đổi Output
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
            Font jetbrainsFont = Font.createFont(Font.TRUETYPE_FONT, fontStream).deriveFont(16f);
            g2d.setFont(jetbrainsFont);
        } catch (Exception e) {
            e.printStackTrace();
            g2d.setFont(new Font("Monospaced", Font.PLAIN, 16));
        }
    }

    private static void drawHeader(Graphics2D g2d, FontMetrics metrics, int y) {
        g2d.setColor(Color.WHITE);
        g2d.drawString("STATUS", COL_STATUS_X, y);
        g2d.drawString("VNDB", COL_VNDB_RIGHT_X - metrics.stringWidth("VNDB"), y);
        g2d.drawString("VNDL", COL_VNDL_RIGHT_X - metrics.stringWidth("VNDL"), y);
    }

    private static int drawHorizontalLine(Graphics2D g2d, int y) {
        g2d.setColor(LINE_COLOR);
        g2d.drawLine(PADDING, y, IMAGE_WIDTH - PADDING, y);
        return y;
    }

    private static int[] drawDataRows(Graphics2D g2d, FontMetrics metrics, int startY,
                                      Map<String, Integer> countsVNDB, Map<String, Integer> countsVNDL,
                                      int... speedList) {
        int totalVNDB = 0;
        int totalVNDL = 0;
        int localY = startY;

        for (String status : STATUS_LIST) {
            localY += ROW_HEIGHT;

            int valVNDB = countsVNDB.getOrDefault(status, 0);
            int valVNDL = countsVNDL.getOrDefault(status, 0);

            if (!"Cancel".equals(status)) {
                totalVNDB += valVNDB;
                totalVNDL += valVNDL;
            }

            // 1. Vẽ cột Status
            g2d.setColor(HIGHLIGHT_STATUSES.contains(status) ? COLOR_ACTIVE_STATUS : COLOR_NORMAL_STATUS);
            g2d.drawString(status, COL_STATUS_X, localY);

            String speedB = "";
            String speedL = "";

            if (speedList != null && speedList.length >= 6) {
                if ("Created".equals(status)) {
                    speedB = (speedList[0] == 0) ? "" : "(+" + speedList[0] + ") ";
                    speedL = (speedList[1] == 0) ? "" : "(+" + speedList[1] + ") ";
                } else if ("Picked".equals(status)) {
                    speedB = (speedList[2] == 0) ? "" : "(+" + speedList[2] + ") ";
                    speedL = (speedList[3] == 0) ? "" : "(+" + speedList[3] + ") ";
                } else if ("Packed".equals(status)) {
                    speedB = (speedList[4] == 0) ? "" : "(+" + speedList[4] + ") ";
                    speedL = (speedList[5] == 0) ? "" : "(+" + speedList[5] + ") ";
                }
            }

            // 2. Vẽ cột VNDB
            String strVndb = String.format("%,d", valVNDB);
            int strVndbWidth = metrics.stringWidth(strVndb);
            int numX_VNDB = COL_VNDB_RIGHT_X - strVndbWidth;

            // Vẽ con số VNDB (Màu chính)
            g2d.setColor(COLOR_VNDB);
            g2d.drawString(strVndb, numX_VNDB, localY);

            // Vẽ speedB màu vàng (đặt phía trước con số)
            if (!speedB.isEmpty()) {
                g2d.setColor(Color.YELLOW);
                int speedBWidth = metrics.stringWidth(speedB);
                g2d.drawString(speedB, numX_VNDB - speedBWidth, localY);
            }

            // 3. Vẽ cột VNDL
            String strVndl = String.format("%,d", valVNDL);
            int strVndlWidth = metrics.stringWidth(strVndl);
            int numX_VNDL = COL_VNDL_RIGHT_X - strVndlWidth;

            // Vẽ con số VNDL (Màu chính)
            g2d.setColor(COLOR_VNDL);
            g2d.drawString(strVndl, numX_VNDL, localY);

            // Vẽ speedL màu vàng (đặt phía trước con số)
            if (!speedL.isEmpty()) {
                g2d.setColor(Color.YELLOW);
                int speedLWidth = metrics.stringWidth(speedL);
                g2d.drawString(speedL, numX_VNDL - speedLWidth, localY);
            }
        }

        return new int[]{totalVNDB, totalVNDL};
    }

    private static void drawFooter(Graphics2D g2d, FontMetrics metrics, int y, int totalVNDB, int totalVNDL) {
        g2d.setColor(Color.YELLOW);
        g2d.drawString("TOTAL ex.Cancel", COL_STATUS_X, y);

        String strTotalVndb = String.format("%,d", totalVNDB);
        g2d.drawString(strTotalVndb, COL_VNDB_RIGHT_X - metrics.stringWidth(strTotalVndb), y);

        String strTotalVndl = String.format("%,d", totalVNDL);
        g2d.drawString(strTotalVndl, COL_VNDL_RIGHT_X - metrics.stringWidth(strTotalVndl), y);
    }

    private static String convertToBase64(BufferedImage image) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        }
    }

}
