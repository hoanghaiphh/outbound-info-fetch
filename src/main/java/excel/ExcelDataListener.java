package easyExcel;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import java.util.Map;
import java.util.Set;

public class ExcelDataListener implements ReadListener<RowData> {

    private final Map<String, Integer> sharedStatusCounts;
    private final Set<String> sharedProcessedColB;

    // Nhận map đếm và set lọc trùng từ bên ngoài truyền vào để cộng dồn
    public ExcelDataListener(Map<String, Integer> sharedStatusCounts, Set<String> sharedProcessedColB) {
        this.sharedStatusCounts = sharedStatusCounts;
        this.sharedProcessedColB = sharedProcessedColB;
    }

    @Override
    public void invoke(RowData data, AnalysisContext context) {
        if (data.getColB() == null) return;

        String valueB = data.getColB().trim();
        if (valueB.isEmpty()) return;

        // Lọc trùng cột B xuyên suốt TẤT CẢ các file
        if (!sharedProcessedColB.contains(valueB)) {
            sharedProcessedColB.add(valueB);

            String valueC = (data.getColC() != null) ? data.getColC().trim() : "";
            if (sharedStatusCounts.containsKey(valueC)) {
                sharedStatusCounts.put(valueC, sharedStatusCounts.get(valueC) + 1);
            }
        }
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        // Không in kết quả ở đây nữa vì ta cần đợi tất cả các file chạy xong
    }
}