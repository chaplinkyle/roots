package com.chaplin.roots.browser;

import com.chaplin.roots.Roots;
import com.chaplin.roots.RootsConfig;
import com.chaplin.roots.internal.DevelopmentEvents;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DevelopmentBrowserTest {
    @Test
    void rendersCompilerFailuresAsTextAndReloadsTheDocument() throws Exception {
        try (var application = Roots.start(RootsConfig.forApplication(Application.class)
                .port(0)
                .development(true)
                .build())) {
            var service = new ChromeDriverService.Builder().usingAnyFreePort().build();
            service.start();
            try {
                var options = new ChromeOptions();
                options.addArguments(
                        "--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--window-size=1100,800"
                );
                var browser = new RemoteWebDriver(service.getUrl(), options);
                try {
                    var wait = new WebDriverWait(browser, Duration.ofSeconds(10));
                    browser.get(application.uri().toString());
                    var initialView = browser.findElement(By.id("roots")).getAttribute("data-roots-view");
                    wait.until(ignored -> application.runtimeSnapshot().activeRequests() >= 2);

                    var inspectorToggle = wait.until(driver -> driver.findElement(By.id("roots-inspector-toggle")));
                    inspectorToggle.click();
                    var inspector = wait.until(driver -> {
                        var panel = driver.findElement(By.id("roots-inspector"));
                        return panel.isDisplayed() && panel.getText().contains("Roots inspector") ? panel : null;
                    });
                    assertTrue(inspector.getText().contains("com.chaplin.roots.browser.pages.Page"), inspector.getText());
                    assertTrue(inspector.getText().contains("Components ("), inspector.getText());
                    assertTrue(inspector.getText().contains("browser-inspection"), inspector.getText());
                    assertTrue(inspector.getText().contains("×2"), inspector.getText());
                    assertTrue(inspector.getText().contains("Actions ("), inspector.getText());
                    assertTrue(inspector.getText().contains("click <button>"), inspector.getText());
                    assertEquals("true", inspectorToggle.getAttribute("aria-expanded"));

                    var hostile = "Compile failed: <img id='roots-injected' src=x onerror='window.rootsInjected=true'>";
                    DevelopmentEvents.failure(Application.class.getName(), hostile);
                    var overlay = wait.until(driver -> driver.findElement(By.id("roots-development-error")));

                    assertTrue(overlay.getText().contains(hostile), overlay.getText());
                    assertTrue(overlay.findElements(By.id("roots-injected")).isEmpty());
                    assertEquals(false, ((JavascriptExecutor) browser)
                            .executeScript("return window.rootsInjected === true"));
                    assertEquals("fixed", overlay.getCssValue("position"));

                    DevelopmentEvents.reload(Application.class.getName());
                    wait.until(driver -> !initialView.equals(
                            driver.findElement(By.id("roots")).getAttribute("data-roots-view")
                    ));
                    assertNotEquals(initialView,
                            browser.findElement(By.id("roots")).getAttribute("data-roots-view"));
                    assertTrue(browser.findElements(By.id("roots-development-error")).isEmpty());
                } finally {
                    browser.quit();
                }
            } finally {
                service.stop();
            }
        }
    }
}
