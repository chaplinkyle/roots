package com.chaplin.roots.examples.workflow;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.context.ConfigurableApplicationContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static com.chaplin.roots.examples.workflow.CustomerRepositoryTest.*;

class WorkflowBrowserTest {
    @TempDir Path temp;
    @ParameterizedTest @ValueSource(strings = {"chrome", "edge", "firefox"})
    void recoversPartialDraftAfterRestartReviewsConflictAndRecoversCommittedLostResponse(String engine) throws Exception {
        var url = "jdbc:h2:file:" + temp.resolve("browser").toString().replace('\\', '/');
        try (var ignored = pool(url, true)) { }
        ConfigurableApplicationContext context = null;
        WebDriver browser = null;
        try {
            context = WorkflowSecurityTest.start(url);
            String base = WorkflowSecurityTest.base(context);
            browser = switch (engine) {
                case "chrome" -> new ChromeDriver(new ChromeOptions().addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--window-size=1280,900"));
                case "edge" -> new EdgeDriver(new EdgeOptions().addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--window-size=1280,900"));
                default -> new FirefoxDriver(new FirefoxOptions().addArguments("-headless", "--width=1280", "--height=900"));
            };
            var driver = browser;
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
            driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(15));
            var wait = new WebDriverWait(driver, Duration.ofSeconds(15));
            login(driver, base);
            click(driver, "New customer");
            wait.until(d -> d.getCurrentUrl().contains("/drafts/"));
            var draftId = UUID.fromString(driver.getCurrentUrl().substring(driver.getCurrentUrl().lastIndexOf('/') + 1));
            var repository = context.getBean(CustomerRepository.class);
            driver.findElement(By.name("company")).sendKeys("Northstar Freight");
            var initial = repository;
            wait.until(d -> initial.draft(EDITOR, draftId).fields().company().equals("Northstar Freight"));
            assertEquals("", repository.draft(EDITOR, draftId).fields().contact());
            screenshot(driver, engine + "-draft-desktop");
            var confirmed = repository.draft(EDITOR, draftId);
            wait.until(d -> Long.toString(confirmed.version()).equals(((JavascriptExecutor) d).executeScript(
                    "return document.querySelector('[data-draft-version]')?.dataset.draftVersion")));
            repository.save(EDITOR, draftId, confirmed.version(), new CustomerRepository.Fields("Changed in another tab", "", ""));
            driver.findElement(By.name("company")).sendKeys(" Local typing");
            wait.until(d -> d.findElement(By.tagName("body")).getText().contains("Your current typing has not replaced the saved draft."));
            assertEquals("Northstar Freight Local typing", driver.findElement(By.name("company")).getDomProperty("value"));
            assertEquals("Changed in another tab", repository.draft(EDITOR, draftId).fields().company());
            driver.get(base + "/app/drafts/" + draftId);
            assertEquals("Changed in another tab", driver.findElement(By.name("company")).getDomProperty("value"));
            driver.findElement(By.name("company")).clear();
            driver.findElement(By.name("company")).sendKeys("Northstar Freight");
            wait.until(d -> initial.draft(EDITOR, draftId).fields().company().equals("Northstar Freight"));
            // Restart the whole server and pool. The next login recovers the same private draft by URL.
            context.close(); context = null;
            context = WorkflowSecurityTest.start(url); base = WorkflowSecurityTest.base(context);
            repository = context.getBean(CustomerRepository.class);
            login(driver, base);
            driver.get(base + "/app/drafts/" + draftId);
            assertEquals("Northstar Freight", driver.findElement(By.name("company")).getDomProperty("value"));
            click(driver, "Save customer");
            wait.until(d -> d.findElement(By.tagName("body")).getText().contains("Enter a contact name."));
            driver.findElement(By.name("contact")).sendKeys("Mina Patel");
            driver.findElement(By.name("email")).sendKeys("mina@example.com");
            var restarted = repository;
            wait.until(d -> restarted.draft(EDITOR, draftId).fields().email().equals("mina@example.com"));
            assertEquals("Mina Patel", driver.findElement(By.name("contact")).getDomProperty("value"));
            // The proxy simulation returns 502 only after the upstream completion has returned success.
            ((JavascriptExecutor) driver).executeScript("""
                    const original = window.fetch.bind(window);
                    window.__workflowResponses = [];
                    window.fetch = async function(...args) {
                      const result = await original.apply(this, args);
                      if (String(args[0]).includes('/_roots/action'))
                        window.__workflowResponses.push({status: result.status, body: await result.clone().text()});
                      if (String(args[0]).includes('/_roots/action') &&
                          String(args[1]?.body).includes('_event=submit') && result.ok) {
                        window.fetch = original;
                        return new Response('Gateway response lost after commit', {status: 502});
                      }
                      return result;
                    };
                    """);
            click(driver, "Save customer");
            wait.until(d -> restarted.draft(EDITOR, draftId).completed());
            wait.until(d -> !d.findElements(By.cssSelector("[data-roots-action-state=uncertain]")).isEmpty());
            var customerId = repository.draft(EDITOR, draftId).customerId();
            driver.get(base + "/app/drafts/" + draftId);
            assertTrue(driver.findElement(By.tagName("body")).getText().contains("Customer saved"));
            driver.findElement(By.linkText("Open saved customer")).click();
            click(driver, "Edit customer");
            wait.until(d -> d.getCurrentUrl().contains("/drafts/"));
            var editId = UUID.fromString(driver.getCurrentUrl().substring(driver.getCurrentUrl().lastIndexOf('/') + 1));
            driver.findElement(By.name("company")).sendKeys(" Group");
            wait.until(d -> restarted.draft(EDITOR, editId).fields().company().equals("Northstar Freight Group"));
            var other = repository.create(OTHER, customerId);
            other = repository.save(OTHER, other.id(), other.version(), new CustomerRepository.Fields("Northstar Freight", "New contact", "new@example.com"));
            repository.complete(OTHER, other.id(), other.version());
            click(driver, "Save customer");
            wait.until(d -> d.findElement(By.tagName("body")).getText().contains("This customer changed"));
            assertEquals("Northstar Freight Group", driver.findElement(By.name("company")).getDomProperty("value"));
            assertTrue(driver.findElement(By.tagName("body")).getText().contains("New contact"));
            screenshot(driver, engine + "-conflict-desktop");
            driver.manage().window().setSize(new org.openqa.selenium.Dimension(390, 844));
            screenshot(driver, engine + "-conflict-mobile");
            assertEquals(Boolean.TRUE, ((JavascriptExecutor) driver).executeScript("return document.documentElement.scrollWidth <= window.innerWidth + 1"));

            // A third edit becomes visible to the SERVER during an autosave whose response
            // is still withheld from the browser. The queued review must carry version 2,
            // which the user saw, rather than accepting server-side render state at version 3.
            var third = repository.create(OTHER, customerId);
            third = repository.save(OTHER, third.id(), third.version(), new CustomerRepository.Fields(
                    "Northstar Freight", "Third contact", "third@example.com"));
            repository.complete(OTHER, third.id(), third.version());
            assertEquals("2", driver.findElement(By.name("reviewVersion")).getDomProperty("value"));
            ((JavascriptExecutor) driver).executeScript("""
                    window.__reviewFetch = window.fetch.bind(window);
                    window.__saveHeld = false;
                    const held = new Promise(resolve => { window.__releaseSave = resolve; });
                    window.fetch = async (...args) => {
                      const response = await window.__reviewFetch(...args);
                      if (String(args[0]).includes('/_roots/action') && String(args[1]?.body).includes('_event=input')) {
                        window.__saveHeld = true;
                        await held;
                      }
                      return response;
                    };
                    """);
            driver.findElement(By.name("contact")).sendKeys(" Sr.");
            wait.until(d -> Boolean.TRUE.equals(((JavascriptExecutor) d).executeScript("return window.__saveHeld")));
            assertEquals("2", driver.findElement(By.name("reviewVersion")).getDomProperty("value"));
            click(driver, "I reviewed the saved values; keep my draft");
            ((JavascriptExecutor) driver).executeScript("window.__releaseSave()");
            wait.until(d -> d.findElement(By.tagName("body")).getText().contains("The customer changed again."));
            assertEquals(1, repository.draft(EDITOR, editId).baseVersion());
            assertEquals(3, repository.customer(VIEWER, customerId).version());
            ((JavascriptExecutor) driver).executeScript("window.fetch = window.__reviewFetch");
            click(driver, "I reviewed the saved values; keep my draft");
            wait.until(d -> d.findElement(By.tagName("body")).getText().contains("Review recorded."));
            click(driver, "Save customer");
            wait.until(d -> d.getCurrentUrl().contains("/customers/"));
            assertEquals(4, repository.customer(VIEWER, customerId).version());
            assertEquals("Northstar Freight Group", repository.customer(VIEWER, customerId).fields().company());
            assertEquals(1, repository.customers(VIEWER, "", "").items().size());
        } catch (Exception | AssertionError failure) {
            failure.printStackTrace(System.err);
            if (browser != null) {
                try {
                    screenshot(browser, engine + "-failure");
                    System.err.println("Workflow browser failure: " + browser.getCurrentUrl() + "\n"
                            + browser.findElement(By.tagName("body")).getText() + "\n"
                            + ((JavascriptExecutor) browser).executeScript("return window.__workflowResponses"));
                } catch (Exception diagnosticFailure) {
                    failure.addSuppressed(diagnosticFailure);
                }
            }
            throw failure;
        } finally {
            try {
                if (browser != null) browser.quit();
            } finally {
                if (context != null) context.close();
            }
        }
    }
    private static void login(WebDriver driver, String base) {
        driver.get(base + "/login");
        driver.findElement(By.name("username")).sendKeys("editor");
        driver.findElement(By.name("password")).sendKeys(WorkflowSecurityTest.PASSWORD);
        driver.findElement(By.cssSelector("button[type=submit]")).click();
        new WebDriverWait(driver, Duration.ofSeconds(15)).until(d -> d.getCurrentUrl().endsWith("/app/customers"));
    }
    private static void click(WebDriver driver, String text) {
        driver.findElement(By.xpath("//button[normalize-space(.)='" + text + "']")).click();
    }
    private static void screenshot(WebDriver driver, String name) throws Exception {
        var directory = Path.of("target", "workflow-screenshots"); Files.createDirectories(directory);
        Files.write(directory.resolve(name + ".png"), ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES));
    }
}
