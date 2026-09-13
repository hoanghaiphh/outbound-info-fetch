package general;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

public class AutoModeConfig {

    private static final Logger log = LogManager.getLogger(AutoModeConfig.class);
    private static final String FILE_PATH = "config/auto-mode.properties";

    public static void saveProperties(String mode, String begTime, String endTime) {
        Properties properties = new Properties();
        properties.setProperty("mode", mode);
        properties.setProperty("from", begTime);
        properties.setProperty("to", endTime);

        File file = new File(FILE_PATH);

        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (parentDir.mkdirs()) {
                log.info("Parent directories created successfully for path: {}", parentDir.getAbsolutePath());
            }
        }

        try (FileOutputStream fos = new FileOutputStream(file)) {
            properties.store(fos, "Auto Mode Configuration");
            log.info("Properties file saved successfully at: {}", file.getAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to save properties file: {}", e.getMessage(), e);
        }
    }

    public static String getProperty(String key) {
        Properties properties = new Properties();
        File file = new File(FILE_PATH);

        if (!file.exists()) {
            log.warn("Properties file does not exist at path: {}", file.getAbsolutePath());
            return null;
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            properties.load(fis);
            String value = properties.getProperty(key);
            log.debug("Retrieved property - Key: {}, Value: {}", key, value);
            return value;
        } catch (IOException e) {
            log.error("Failed to read properties file: {}", e.getMessage(), e);
            return null;
        }
    }

}