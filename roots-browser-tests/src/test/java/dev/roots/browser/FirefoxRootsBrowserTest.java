package dev.roots.browser;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.firefox.GeckoDriverService;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.service.DriverService;

final class FirefoxRootsBrowserTest extends RootsBrowserTest {
    @Override
    protected String browserName() {
        return "Firefox";
    }

    @Override
    protected DriverService createBrowserService() {
        return new GeckoDriverService.Builder().usingAnyFreePort().build();
    }

    @Override
    protected WebDriver createBrowser(DriverService service) {
        var options = new FirefoxOptions();
        options.addArguments("-headless");
        options.addPreference("layout.css.devPixelsPerPx", "1.0");
        return new RemoteWebDriver(service.getUrl(), options);
    }
}
