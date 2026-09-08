package com.chaplin.roots.browser;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.edge.EdgeDriverService;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.service.DriverService;

final class EdgeRootsBrowserTest extends RootsBrowserTest {
    @Override
    protected String browserName() {
        return "Edge";
    }

    @Override
    protected DriverService createBrowserService() {
        return new EdgeDriverService.Builder().usingAnyFreePort().build();
    }

    @Override
    protected WebDriver createBrowser(DriverService service) {
        var options = new EdgeOptions();
        options.addArguments(
                "--headless=new",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--no-first-run",
                "--no-default-browser-check",
                "--window-size=1280,900"
        );
        return new RemoteWebDriver(service.getUrl(), options);
    }
}
