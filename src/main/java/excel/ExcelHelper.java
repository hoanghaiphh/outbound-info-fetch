package easyExcel;

import com.alibaba.excel.EasyExcel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

public class ExcelHelper {

    private static final List<String> statusList = Arrays.asList(
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

    /**
     * Quét toàn bộ file excel trong thư mục và trả về Map chứa số lượng của từng trạng thái
     */
    public static Map<String, Integer> getStatusCountsFromDirectory(String dirPath) {
        // Khởi tạo Map kết quả với giá trị mặc định là 0
        Map<String, Integer> statusCounts = new HashMap<>();
        for (String status : statusList) {
            statusCounts.put(status, 0);
        }

        Set<String> processedColB = new HashSet<>();
        Path path = Paths.get(dirPath);

        if (!Files.exists(path) || !Files.isDirectory(path)) {
            System.err.println("Cảnh báo: Thư mục không tồn tại hoặc không hợp lệ: " + dirPath);
            return statusCounts;
        }

        try (Stream<Path> paths = Files.list(path)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".xlsx"))
                    .forEach(file -> {
                        try {
                            EasyExcel.read(file.toFile(), RowData.class,
                                            new ExcelDataListener(statusCounts, processedColB))
                                    .sheet(0)
                                    .headRowNumber(1)
                                    .doRead();
                        } catch (Exception e) {
                            System.err.println("Lỗi đọc file " + file.getFileName() + ": " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            System.err.println("Lỗi khi duyệt thư mục " + dirPath + ": " + e.getMessage());
        }

        return statusCounts;
    }

    /**
     * Hàm so sánh kết quả giữa 2 thư mục và hiển thị dạng bảng
     */
    public static void compareDirectories(String dirPath1, String dirPath2) {
        System.out.println("Đang quét dữ liệu từ các thư mục, vui lòng đợi...");

        long startTime = System.currentTimeMillis();

        // Lấy dữ liệu của từng thư mục
        Map<String, Integer> countsVndl = getStatusCountsFromDirectory(dirPath1);
        Map<String, Integer> countsVndb = getStatusCountsFromDirectory(dirPath2);

        long duration = System.currentTimeMillis() - startTime;

        // In bảng so sánh
        System.out.println("\n====================== BẢNG SO SÁNH SỐ LIỆU ======================");
        System.out.println("------------------------------------------------------------------");
        // %-20s: Căn lề trái 20 ký tự cho cột Trạng thái
        // %15s: Căn lề phải 15 ký tự cho cột số liệu
        System.out.printf("| %-20s | %15s | %15s |\n", "Trạng thái", "VNDL", "VNDB");
        System.out.println("------------------------------------------------------------------");

        int totalVndl = 0;
        int totalVndb = 0;

        for (String status : statusList) {
            int valVndl = countsVndl.getOrDefault(status, 0);
            int valVndb = countsVndb.getOrDefault(status, 0);

            totalVndl += valVndl;
            totalVndb += valVndb;

            System.out.printf("| %-20s | %15d | %15d |\n", status, valVndl, valVndb);
        }

        System.out.println("------------------------------------------------------------------");
        System.out.printf("| %-20s | %15d | %15d |\n", "TỔNG CỘNG", totalVndl, totalVndb);
        System.out.println("------------------------------------------------------------------");
        System.out.printf("Thời gian xử lý hoàn tất: %,d ms\n", duration);
        System.out.println("==================================================================");
    }

    public static void main(String[] args) {
        // Thực hiện so sánh 2 thư mục của bạn
        compareDirectories("output/VNDL", "output/VNDB");
    }
}