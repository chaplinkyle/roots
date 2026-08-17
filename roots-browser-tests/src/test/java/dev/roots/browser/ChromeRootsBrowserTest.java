package dev.roots.browser;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.chromium.HasCdp;
import org.openqa.selenium.remote.Augmenter;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.service.DriverService;

import java.util.Map;

final class ChromeRootsBrowserTest extends RootsBrowserTest {
    @Override
    protected String browserName() {
        return "Chrome";
    }

    @Override
    protected DriverService createBrowserService() {
        return new ChromeDriverService.Builder().usingAnyFreePort().build();
    }

    @Override
    protected WebDriver createBrowser(DriverService service) {
        var options = new ChromeOptions();
        options.addArguments(
                "--headless=new",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--window-size=1280,900"
        );
        return new Augmenter().augment(new RemoteWebDriver(service.getUrl(), options));
    }

    @Test
    void reconnectsAndAppliesAnUpdateQueuedDuringANetworkOutage() {
        verifyReconnectsAndAppliesAnUpdateQueuedDuringANetworkOutage(
                this::enableNetworkEmulation,
                this::setNetworkOffline
        );
    }

    private void enableNetworkEmulation() {
        ((HasCdp) browser).executeCdpCommand("Network.enable", Map.of());
    }

    private void setNetworkOffline(boolean offline) {
        ((HasCdp) browser).executeCdpCommand("Network.emulateNetworkConditions", Map.<String, Object>of(
                "offline", offline,
                "latency", 0,
                "downloadThroughput", -1,
                "uploadThroughput", -1
        ));
    }
}
