package excel;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;

import java.util.Map;
import java.util.Set;

public class ExcelDataListener implements ReadListener<RowData> {

    private final Map<String, Integer> sharedStatusCounts;
    private final Set<String> sharedProcessedColB;
    private final String target3pl;

    public ExcelDataListener(Map<String, Integer> sharedStatusCounts, Set<String> sharedProcessedColB) {
        this.sharedStatusCounts = sharedStatusCounts;
        this.sharedProcessedColB = sharedProcessedColB;
        this.target3pl = null;
    }

    public ExcelDataListener(Map<String, Integer> sharedStatusCounts, Set<String> sharedProcessedColB, String target3pl) {
        this.sharedStatusCounts = sharedStatusCounts;
        this.sharedProcessedColB = sharedProcessedColB;
        this.target3pl = target3pl;
    }

    @Override
    public void invoke(RowData data, AnalysisContext context) {
        if (data.getColB() == null) return;

        String valueB = data.getColB().trim();
        if (valueB.isEmpty()) return;

        if (target3pl != null) {
            String valueAF = (data.getColAF() != null) ? data.getColAF().trim() : "";
            if (!target3pl.equalsIgnoreCase(valueAF)) {
                return;
            }
        }

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

    }
}