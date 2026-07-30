package seatalk;

import java.util.List;

public class RePrintOrderInfo {
    private final String orderNumber;
    private final int printCount;
    private final List<PrintLog> printLogs;

    public RePrintOrderInfo(String orderNumber, int printCount, List<PrintLog> printLogs) {
        this.orderNumber = orderNumber;
        this.printCount = printCount;
        this.printLogs = printLogs;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public int getPrintCount() {
        return printCount;
    }

    public List<PrintLog> getPrintLogs() {
        return printLogs;
    }

    public static class PrintLog {
        private final String timeFormatted;
        private final String operator;

        public PrintLog(String timeFormatted, String operator) {
            this.timeFormatted = timeFormatted;
            this.operator = operator;
        }

        public String getTimeFormatted() {
            return timeFormatted;
        }

        public String getOperator() {
            return operator;
        }
    }
}