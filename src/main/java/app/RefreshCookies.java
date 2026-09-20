package app;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import common.config.CookiesConfig;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static common.constants.GlobalConstants.DEFAULT_PW;
import static common.constants.GlobalConstants.DEFAULT_USER;

public class RefreshCookies {

    private static final Logger log = LogManager.getLogger(RefreshCookies.class);

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public static void main(String[] args) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                log.info("Checking cookies...");

                boolean isVNDBValid = CookiesConfig.isCookiesValid(DEFAULT_USER, "VNDB");
                boolean isVNDLValid = CookiesConfig.isCookiesValid(DEFAULT_USER, "VNDL");

                if (!isVNDBValid || !isVNDLValid) {
                    CookiesConfig.loginAndSaveCookies(DEFAULT_USER, DEFAULT_PW);
                } else {
                    log.info("Cookies still valid.");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 1, TimeUnit.HOURS);
    }
}