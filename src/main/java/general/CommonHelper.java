package general;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.stream.Stream;

import static general.GlobalConstants.DATE_TIME_FORMATTER;

public class CommonHelper {

    public static void cleanUpDirectory(String dirPath) {
        Path path = Paths.get(dirPath);
        if (!Files.exists(path)) return;

        try (Stream<Path> walk = Files.walk(path)) {
            walk.filter(p -> !p.equals(path))
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to delete: " + p + " | " + e.getMessage());
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException("Failed to clean up directory! " + e.getMessage());
        }
    }

    public static String[] getWorkingTimeRange(boolean isForward) {
        LocalDateTime now = LocalDateTime.now();
        LocalTime boundaryTime = LocalTime.of(18, 0, 0);

        LocalDateTime startTime;
        LocalDateTime endTime;

        if (isForward) {
            if (now.toLocalTime().isBefore(boundaryTime)) {
                startTime = now.minusDays(1).with(boundaryTime);
                endTime = now.with(boundaryTime);
            } else {
                startTime = now.with(boundaryTime);
                endTime = now.plusDays(1).with(boundaryTime);
            }
        } else {
            startTime = now.minusDays(1).with(boundaryTime);
            endTime = now.with(boundaryTime);
        }

        String startStr = startTime.format(DATE_TIME_FORMATTER);
        String endStr = endTime.format(DATE_TIME_FORMATTER);

        return new String[]{startStr, endStr};
    }

}
