package easyExcel;

import com.alibaba.excel.annotation.ExcelProperty;

public class RowData {
    @ExcelProperty(index = 1) // Cột B
    private String colB;

    @ExcelProperty(index = 2) // Cột C
    private String colC;

    // Getters and Setters
    public String getColB() { return colB; }
    public void setColB(String colB) { this.colB = colB; }

    public String getColC() { return colC; }
    public void setColC(String colC) { this.colC = colC; }
}