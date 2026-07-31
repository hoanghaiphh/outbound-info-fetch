package app;

import wms.CookiesConfig;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static general.GlobalConstants.DEFAULT_PW;
import static general.GlobalConstants.DEFAULT_USER;

public class RefreshCookies {

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public static void main(String[] args) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                System.out.println("Checking cookies...");

                boolean isVNDBValid = CookiesConfig.isCookiesValid(DEFAULT_USER, "VNDB");
                boolean isVNDLValid = CookiesConfig.isCookiesValid(DEFAULT_USER, "VNDL");

                if (!isVNDBValid || !isVNDLValid) {
                    CookiesConfig.loginAndSaveCookies(DEFAULT_USER, DEFAULT_PW);
                } else {
                    System.out.println("Cookies still valid.");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 1, TimeUnit.HOURS);
    }
}