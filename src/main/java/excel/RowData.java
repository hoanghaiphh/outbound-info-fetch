package excel;

import com.alibaba.excel.annotation.ExcelProperty;

public class RowData {
    @ExcelProperty(index = 1)
    private String colB;

    @ExcelProperty(index = 2)
    private String colC;

    public String getColB() {
        return colB;
    }

    public void setColB(String colB) {
        this.colB = colB;
    }

    public String getColC() {
        return colC;
    }

    public void setColC(String colC) {
        this.colC = colC;
    }
}