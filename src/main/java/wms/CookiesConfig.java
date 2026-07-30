package wms;

import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static general.GlobalConstants.*;

public class CookiesConfig {

    private static final String URL = "https://wms.business.accounts.shopee.com/authenticate/login?lang=en&client_id=19&next=https%3A%2F%2Fwms.ssc.shopee.vn%2Fv2%2Ftob%2Fcallback&google_login_redirect=https%3A%2F%2Fwms.ssc.shopee.vn%2Fv2%2Fgoogle%2Flogin";

    private static final String USER_TEXTBOX = "input#warehouse-management-system-authKey";
    private static final String PASSWORD_TEXTBOX = "input#warehouse-management-system-password";
    private static final String LOGIN_BTN = "button.ODBamh";

    private static final String WHS_SELECT = "span.ssc-select-arrow";
    private static final String VNDB = "//span[text()='VN - VNDB']";
    private static final String VNDL = "//span[text()='VN - VNDL']";

    public static boolean isCookiesValid(String userName, String warehouse) {
        try {
            File directory = new File(COOKIES_DIR);
            String fileName = userName + "_" + warehouse + ".json";
            File cookiesFile = new File(directory, fileName);

            if (!cookiesFile.exists()) {
                System.out.println("Cookies not found!");
                return false;

            } else {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> data = mapper.readValue(cookiesFile, Map.class);
                long createdTime = ((Number) data.get("createdTime")).longValue();

                if ((System.currentTimeMillis() - createdTime) > (72 * 60 * 60 * 1000L)) {
                    System.out.println("Cookies expired!");
                    return false;

                } else {
                    return true;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to validate cookies!", e);
        }
    }

    public static Map<String, String> loadCookies(String userName, String warehouse) {
        try {
            File directory = new File(COOKIES_DIR);
            String fileName = userName + "_" + warehouse + ".json";
            File cookiesFile = new File(directory, fileName);

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> data = mapper.readValue(cookiesFile, Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cookies = (List<Map<String, Object>>) data.get("cookies");
            Map<String, String> restAssuredCookies = new HashMap<>();

            for (Map<String, Object> cookieObj : cookies) {
                String name = (String) cookieObj.get("name");
                String value = (String) cookieObj.get("value");
                if (name != null) restAssuredCookies.put(name, value != null ? value : "");
            }

            return restAssuredCookies;

        } catch (Exception e) {
            throw new RuntimeException("Failed to load cookies from file!", e);
        }
    }

    //todo
    public static void loginAndSaveCookies(String userName, String password) {
        System.out.println("Generating new cookies...");

        WebDriver driver = initBrowserThenNavigateTo(URL);

        sendKeysToVisibleElement(driver, By.cssSelector(USER_TEXTBOX), userName);
        sendKeysToVisibleElement(driver, By.cssSelector(PASSWORD_TEXTBOX), password);
        clickOnClickableElement(driver, By.cssSelector(LOGIN_BTN));

        clickOnClickableElement(driver, By.cssSelector(WHS_SELECT));
        clickOnClickableElement(driver, By.xpath(VNDB));
        sleepInSec(10); //todo
        saveCookies(driver, userName, "VNDB");

        clickOnClickableElement(driver, By.cssSelector(WHS_SELECT));
        clickOnClickableElement(driver, By.xpath(VNDL));
        sleepInSec(10); //todo
        saveCookies(driver, userName, "VNDL");

        driver.quit();
    }

    private static WebDriver initBrowserThenNavigateTo(String url) {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--window-size=1024,768");

        WebDriver driver = new ChromeDriver(options);
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(60));
        driver.get(url);

        return driver;
    }

    private static void sleepInSec(long timeInSec) {
        try {
            Thread.sleep(timeInSec * 1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static void sendKeysToVisibleElement(WebDriver driver, By locator, String keysToSend) {
        new WebDriverWait(driver, Duration.ofSeconds(60))
                .until(ExpectedConditions.visibilityOfElementLocated(locator))
                .sendKeys(keysToSend);
    }

    private static void clickOnClickableElement(WebDriver driver, By locator) {
        new WebDriverWait(driver, Duration.ofSeconds(60))
                .until(ExpectedConditions.elementToBeClickable(locator))
                .click();
    }

    private static void saveCookies(WebDriver driver, String userName, String warehouse) {
        try {
            Set<Cookie> cookies = driver.manage().getCookies();

            Map<String, Object> data = new HashMap<>();
            data.put("createdTime", System.currentTimeMillis());
            data.put("cookies", cookies);

            File directory = new File(COOKIES_DIR);
            if (!directory.exists()) directory.mkdirs();

            String fileName = userName + "_" + warehouse + ".json";
            File file = new File(directory, fileName);

            ObjectMapper mapper = new ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, data);
            // System.out.println(" -> Cookies saved successfully at: " + file.getAbsolutePath());

        } catch (Exception e) {
            throw new RuntimeException("Failed to save cookies for user: " + userName, e);
        }
    }

}
