package general;

import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;

public class GlobalConstants {

    public static final String ANSI_RESET = "\u001B[0m";
    public static final String ANSI_RED = "\u001B[91m";
    public static final String ANSI_GREEN = "\u001B[92m";
    public static final String ANSI_CYAN = "\u001B[96m";
    public static final String ANSI_BLUE = "\u001B[94m";
    public static final String ANSI_YELLOW = "\u001B[33m";

    public static final String COOKIES_DIR = System.getProperty("user.dir") + File.separator + "cookies-store";

    public static final String OUTPUT_DIR = System.getProperty("user.dir") + File.separator + "output";

    public static final Map<String, String> CREDENTIALS = Map.ofEntries(
            entry("669432", "AnhQuan@250621"),
            entry("357513", "MIt280323")
    );

    public static final List<String> STATUS_LIST = Arrays.asList(
            "Created",
            "Pending Pick", "Picking", "Picked", "Pick Fail",
            "Checking", "Checked", "Packing", "Packed",
            "Shipping", "Outbound",
            "Cancel"
    );

    public static final Set<String> ACTIVE_STATUSES = Set.of(
            "Created", "Picked", "Packed", "Outbound"
    );

    public static final String DATE_TIME_PATTERN = "yyyy/MM/dd HH:mm:ss";

    public static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);

}
