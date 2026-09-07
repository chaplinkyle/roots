package com.chaplin.roots.browser;

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
    void widgetDeadlineAbortsAnUnresponsiveUpdateWithoutBlockingTheView() {
        var script = (org.openqa.selenium.JavascriptExecutor) browser;
        browser.get(application.uri().resolve("widgets").toString());
        wait.until(driver -> driver.findElements(org.openqa.selenium.By.id("widget-first-editor")).size() == 1);
        script.executeScript("window.widgetErrors=[]; window.addEventListener('roots:widget-error', e=>widgetErrors.push(e.detail.phase))");
        browser.findElement(org.openqa.selenium.By.id("widget-slow")).click();
        new org.openqa.selenium.support.ui.WebDriverWait(browser, java.time.Duration.ofSeconds(35))
                .until(ignored -> Boolean.TRUE.equals(script.executeScript("return widgetErrors.includes('update')")));
        org.junit.jupiter.api.Assertions.assertEquals("Fallback 1", browser.findElement(org.openqa.selenium.By.id("widget-first")).getText());
        org.junit.jupiter.api.Assertions.assertEquals(1, ((Number) script.executeScript("return widgetTrace.filter(e=>e.id==='widget-first' && e.event==='abort').length")).intValue());
        browser.findElement(org.openqa.selenium.By.id("widget-update")).click();
        wait.until(driver -> driver.findElement(org.openqa.selenium.By.id("widget-server-value")).getText().equals("2"));
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
