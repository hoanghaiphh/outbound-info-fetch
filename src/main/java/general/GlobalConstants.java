package general;

import java.io.File;
import java.time.ZoneId;
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
    public static final String TMP_OUTPUT_DIR = System.getProperty("user.dir") + File.separator + "output-tmp";

    public static final Map<String, String> CREDENTIALS = Map.ofEntries(
            entry("669432", "AnhQuan@250621"),
            entry("357513", "MIt280323"),
            entry("336104", "@Shopee123")
    );

    public static final String DEFAULT_USER = "669432";
    public static final String DEFAULT_PW = "AnhQuan@250621";

    public static final List<String> STATUS_LIST = Arrays.asList(
            "Created",
            "Pending Pick", "Picking", "Picked", "Pick Fail",
            "Checking", "Checked", "Packing", "Packed",
            "Shipping", "Outbound",
            "Cancel"
    );

    public static final Set<String> HIGHLIGHT_STATUSES = Set.of(
            "Created", "Picked", "Packed", "Outbound"
    );

    public static final String DATE_TIME_PATTERN = "yyyy/MM/dd HH:mm:ss";
    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);

    public static final DateTimeFormatter TIME_FORMATTER
            = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    public static final String BACKUP_GROUP_ID = "MDI5NTU5MzE4NTU2";
    public static final String AMON_GROUP_ID = "MzE3MjI0MjY1MTEw";

}
