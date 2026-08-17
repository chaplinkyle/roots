package dev.roots.browser;

import com.deque.html.axecore.results.Rule;
import com.deque.html.axecore.selenium.AxeBuilder;
import dev.roots.Roots;
import dev.roots.RootsCache;
import dev.roots.RootsConfig;
import dev.roots.RunningApplication;
import dev.roots.AuthorizationPolicy;
import dev.roots.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.remote.service.DriverService;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.nio.file.Path;
import java.nio.file.Files;
import java.awt.Color;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class RootsBrowserTest {
    protected RunningApplication application;
    protected DriverService browserService;
    protected WebDriver browser;
    protected WebDriverWait wait;
    private Path imageFixture;
    private Path fontFixture;

    @BeforeAll
    void start() throws Exception {
        var classes = Path.of(Application.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        imageFixture = classes.resolve("public/images/browser.png");
        fontFixture = classes.resolve("public/fonts/browser.woff2");
        Files.createDirectories(imageFixture.getParent());
        Files.createDirectories(fontFixture.getParent());
        var image = new BufferedImage(8, 4, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(20, 80, 180, 160));
        graphics.fillRect(0, 0, 8, 4);
        graphics.dispose();
        ImageIO.write(image, "png", imageFixture.toFile());
        Files.write(fontFixture, new byte[]{'w', 'O', 'F', '2', 0, 0, 0, 0});
        application = Roots.start(RootsConfig.forApplication(Application.class)
                .port(0)
                .development(false)
                .build());

        browserService = createBrowserService();
        try {
            browserService.start();
            browser = createBrowser(browserService);
        } catch (RuntimeException exception) {
            browserService.stop();
            application.close();
            throw exception;
        } catch (Exception exception) {
            browserService.stop();
            application.close();
            throw new IllegalStateException("Could not start " + browserName() + " WebDriver", exception);
        }
        wait = new WebDriverWait(browser, Duration.ofSeconds(10));
    }

    @AfterAll
    void stop() {
        if (browser != null) {
            browser.quit();
        }
        if (browserService != null) {
            browserService.stop();
        }
        if (application != null) {
            application.close();
        }
        try {
            if (imageFixture != null) Files.deleteIfExists(imageFixture);
            if (fontFixture != null) Files.deleteIfExists(fontFixture);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not clean browser asset fixtures", failure);
        }
    }

    protected abstract String browserName();

    protected abstract DriverService createBrowserService();

    protected abstract WebDriver createBrowser(DriverService service);

    @Test
    void roundTripsApplicationCookiesThroughPageAndActionContexts() {
        browser.get(application.uri().toString());
        browser.manage().addCookie(new Cookie.Builder("browser-seed", "blue").path("/").build());
        browser.navigate().refresh();
        wait.until(driver -> driver.findElement(By.id("cookie-status")).getText().contains("seed=blue"));

        browser.findElement(By.id("cookie-set")).click();
        wait.until(driver -> {
            var cookie = driver.manage().getCookieNamed("browser-preference");
            return cookie != null && cookie.getValue().equals("dense");
        });
        assertTrue(browser.findElement(By.id("cookie-status")).getText().contains("set from seed=blue"));

        browser.findElement(By.id("cookie-delete")).click();
        wait.until(driver -> driver.manage().getCookieNamed("browser-preference") == null);
        assertTrue(browser.findElement(By.id("cookie-status")).getText().contains("deleted value=dense"));
    }

    @Test
    void providesNativeModalFocusEscapeBackdropAndFocusReturn() {
        browser.get(application.uri().toString());
        wait.until(driver -> driver.getTitle().equals("Roots browser fixture"));
        var script = (JavascriptExecutor) browser;
        var opener = browser.findElement(By.id("open-overlay"));
        opener.click();
        wait.until(driver -> driver.findElements(By.cssSelector(
                "[data-roots-portal-host=\"browser-overlay\"] dialog[data-roots-modal]"
        )).size() == 1);
        var dialog = browser.findElement(By.cssSelector("dialog[data-roots-modal]"));
        assertEquals(Boolean.TRUE, script.executeScript("return arguments[0].matches(':modal')", dialog));
        wait.until(driver -> driver.switchTo().activeElement().getAttribute("id").equals("portal-name"));
        for (var index = 0; index < 4; index++) {
            browser.switchTo().activeElement().sendKeys(Keys.TAB);
            assertEquals(Boolean.TRUE, script.executeScript(
                    "return arguments[0] === document.activeElement || arguments[0].contains(document.activeElement)",
                    dialog
            ));
        }

        browser.switchTo().activeElement().sendKeys(Keys.ESCAPE);
        wait.until(driver -> driver.findElements(By.cssSelector("[data-roots-portal-host]")).isEmpty());
        wait.until(driver -> driver.switchTo().activeElement().getAttribute("id").equals("open-overlay"));

        browser.findElement(By.id("open-overlay")).click();
        wait.until(driver -> driver.findElements(By.cssSelector("dialog[data-roots-modal]")).size() == 1);
        dialog = browser.findElement(By.cssSelector("dialog[data-roots-modal]"));
        script.executeScript("arguments[0].dispatchEvent(new MouseEvent('click', { bubbles: true }))", dialog);
        wait.until(driver -> driver.findElements(By.cssSelector("[data-roots-portal-host]")).isEmpty());
        wait.until(driver -> driver.switchTo().activeElement().getAttribute("id").equals("open-overlay"));

        browser.findElement(By.id("open-locked-overlay")).click();
        wait.until(driver -> driver.findElements(By.cssSelector("dialog[data-roots-modal]")).size() == 1);
        dialog = browser.findElement(By.cssSelector("dialog[data-roots-modal]"));
        assertEquals("false", dialog.getAttribute("data-roots-modal-backdrop"));
        script.executeScript("arguments[0].dispatchEvent(new MouseEvent('click', { bubbles: true }))", dialog);
        script.executeAsyncScript("const done = arguments[arguments.length - 1]; setTimeout(done, 250)");
        assertEquals(1, browser.findElements(By.cssSelector("[data-roots-portal-host]")).size());
        assertEquals(Boolean.TRUE, script.executeScript("return arguments[0].matches(':modal')", dialog));
        browser.switchTo().activeElement().sendKeys(Keys.ESCAPE);
        wait.until(driver -> driver.findElements(By.cssSelector("[data-roots-portal-host]")).isEmpty());
        wait.until(driver -> driver.switchTo().activeElement().getAttribute("id").equals("open-locked-overlay"));
    }

    @Test
    void loadsOptimizedImagesAndSynchronizesFontHeadAssetsInARealBrowser() {
        browser.get(application.uri().resolve("assets").toString());
        wait.until(driver -> driver.getTitle().equals("Roots asset fixture"));
        var image = browser.findElement(By.id("optimized-image"));
        wait.until(ignored -> ((Number) ((JavascriptExecutor) browser)
                .executeScript("return arguments[0].naturalWidth", image)).intValue() > 0);

        assertTrue(image.getAttribute("currentSrc").contains("/_roots/image?"));
        assertTrue(image.getAttribute("srcset").contains("w=4&q=80 4w"));
        assertEquals("async", image.getAttribute("decoding"));
        assertEquals("lazy", image.getAttribute("loading"));
        assertEquals(1, browser.findElements(By.cssSelector("link[data-roots-font]")).size());
        assertEquals(1, browser.findElements(By.cssSelector("link[data-roots-font-preload]")).size());

        browser.findElement(By.id("asset-home")).click();
        wait.until(driver -> driver.getTitle().equals("Roots browser fixture"));
        wait.until(driver -> driver.findElements(By.cssSelector("link[data-roots-font]")).isEmpty()
                && driver.findElements(By.cssSelector("link[data-roots-font-preload]")).isEmpty());
        assertEquals(List.of(application.uri().resolve("base.css").toString()), stylesheetUrls());
    }

    @Test
    void passesWcag22AaAndBestPracticeAuditsAcrossLiveUiStates() {
        browser.get(application.uri().toString());
        wait.until(driver -> driver.getTitle().equals("Roots browser fixture"));
        assertAccessible("initial live document");

        var email = browser.findElement(By.id("validation-email"));
        email.sendKeys("invalid");
        browser.findElement(By.id("validation-submit")).click();
        wait.until(driver -> !driver.findElement(By.id("validation-summary")).getText().isBlank());
        assertAccessible("server validation errors");

        browser.findElement(By.id("open-overlay")).click();
        wait.until(driver -> driver.findElements(By.cssSelector(
                "body > [data-roots-portal-host=\"browser-overlay\"] dialog[open]"
        )).size() == 1);
        assertAccessible("open portal dialog");

        browser.findElement(By.id("portal-close")).click();
        wait.until(driver -> driver.findElements(By.cssSelector("[data-roots-portal-host]")).isEmpty());
        browser.get(application.uri().resolve("themed").toString());
        wait.until(driver -> driver.getTitle().equals("Roots themed fixture"));
        assertAccessible("client-navigation destination");
    }

    @Test
    void appliesSelectionBlurAndAccessibleAnnouncementEffectsWithoutApplicationScript() {
        browser.get(application.uri().toString());
        wait.until(driver -> driver.getTitle().equals("Roots browser fixture"));
        var script = (JavascriptExecutor) browser;
        script.executeScript("""
                window.__rootsAnnouncements = [];
                window.addEventListener('roots:announce', event => {
                  window.__rootsAnnouncements.push(event.detail);
                });
                """);

        browser.findElement(By.id("effect-run")).click();
        wait.until(driver -> "effect-input".equals(
                driver.switchTo().activeElement().getAttribute("id")));
        var input = browser.findElement(By.id("effect-input"));
        assertEquals(0L, script.executeScript("return arguments[0].selectionStart", input));
        assertEquals((long) "select this text".length(),
                script.executeScript("return arguments[0].selectionEnd", input));
        wait.until(ignored -> ((List<?>) script.executeScript("return window.__rootsAnnouncements")).size() == 2);
        assertEquals("Settings saved", browser.findElement(By.cssSelector(
                "[data-roots-announcer=\"polite\"]")).getAttribute("textContent"));
        assertEquals("Critical update available", browser.findElement(By.cssSelector(
                "[data-roots-announcer=\"assertive\"]")).getAttribute("textContent"));
        assertEquals("fixed", script.executeScript("""
                return getComputedStyle(document.querySelector('[data-roots-announcer="polite"]')).position;
                """));

        script.executeScript("""
                arguments[0].dispatchEvent(new KeyboardEvent('keydown', {
                  key: 'Escape', code: 'Escape', bubbles: true
                }));
                """, input);
        wait.until(driver -> !"effect-input".equals(
                driver.switchTo().activeElement().getAttribute("id")));
        wait.until(ignored -> browser.findElement(By.cssSelector(
                "[data-roots-announcer=\"polite\"]")).getAttribute("textContent").equals("Focus cleared"));
        assertEquals("No extended event yet", browser.findElement(By.id("event-result")).getText(),
                "framework-directed blur must not recursively invoke a bound blur action");
        assertAccessible("typed effect announcements");
    }

    @Test
    void commitsAComponentBasedNotFoundPageThroughClientNavigation() {
        browser.get(application.uri().toString());
        wait.until(driver -> driver.getTitle().equals("Roots browser fixture"));
        var script = (JavascriptExecutor) browser;
        script.executeScript("""
                window.__rootsNotFoundSentinel = 'same-document';
                window.__rootsNotFoundNavigations = [];
                window.addEventListener('roots:navigate', event => {
                  window.__rootsNotFoundNavigations.push(event.detail);
                });
                """);

        browser.findElement(By.id("missing-link")).click();
        wait.until(driver -> URI.create(driver.getCurrentUrl()).getPath().equals("/missing-customer"));
        wait.until(driver -> driver.getTitle().equals("Roots not found fixture"));
        assertEquals("same-document", script.executeScript("return window.__rootsNotFoundSentinel"),
                "a valid Roots 404 should commit without a document reload");
        assertEquals("That page wandered off", browser.findElement(By.id("not-found-heading")).getText());
        assertEquals("/missing-customer", browser.findElement(By.id("not-found-path")).getText());
        assertEquals("browser-fixture", browser.findElement(By.tagName("main")).getAttribute("id"));
        wait.until(ignored -> ((List<?>) script.executeScript(
                "return window.__rootsNotFoundNavigations")).size() == 1);
        assertEquals(404L, script.executeScript(
                "return window.__rootsNotFoundNavigations[0].status"));

        browser.findElement(By.id("not-found-action")).click();
        wait.until(driver -> driver.findElement(By.id("not-found-action")).getText().equals("Recovery 1"));
        assertAccessible("custom live not-found page");

        browser.findElement(By.id("not-found-home")).click();
        wait.until(driver -> URI.create(driver.getCurrentUrl()).getPath().equals("/"));
        wait.until(driver -> driver.getTitle().equals("Roots browser fixture"));
    }

    @Test
    void commitsASafeLiveErrorPageForFailedClientNavigation() {
        browser.get(application.uri().toString());
        wait.until(driver -> driver.getTitle().equals("Roots browser fixture"));
        var script = (JavascriptExecutor) browser;
        script.executeScript("""
                window.__rootsErrorSentinel = 'same-document';
                window.__rootsErrorNavigations = [];
                window.addEventListener('roots:navigate', event => {
                  window.__rootsErrorNavigations.push(event.detail);
                });
                """);

        browser.findElement(By.id("broken-link")).click();
        wait.until(driver -> URI.create(driver.getCurrentUrl()).getPath().equals("/broken"));
        wait.until(driver -> driver.getTitle().equals("Roots error fixture"));
        assertEquals("same-document", script.executeScript("return window.__rootsErrorSentinel"));
        assertEquals("We could not load that page",
                browser.findElement(By.id("error-page-heading")).getText());
        assertTrue(browser.findElement(By.id("error-page-trace")).getText()
                .startsWith("Support reference: "));
        assertTrue(browser.findElements(By.id("browser-fixture")).isEmpty(),
                "the global error page must not depend on the ordinary root layout");
        wait.until(ignored -> ((List<?>) script.executeScript(
                "return window.__rootsErrorNavigations")).size() == 1);
        assertEquals(500L, script.executeScript("return window.__rootsErrorNavigations[0].status"));

        browser.findElement(By.id("error-page-action")).click();
        wait.until(driver -> driver.findElement(By.id("error-page-action")).getText().equals("Reports 1"));
        assertAccessible("custom live error page");

        browser.findElement(By.id("error-page-home")).click();
        wait.until(driver -> URI.create(driver.getCurrentUrl()).getPath().equals("/"));
        wait.until(driver -> driver.getTitle().equals("Roots browser fixture"));
    }

    @Test
    void keepsTheLatestNavigationAndRejectsPayloadsFromAnOldView() throws Exception {
        browser.get(application.uri().toString());
        wait.until(driver -> driver.getTitle().equals("Roots browser fixture"));
        var script = (JavascriptExecutor) browser;
        script.executeScript("""
                window.__rootsRaceMarker = 'same-document';
                window.__rootsStale = [];
                window.addEventListener('roots:stale', event => {
                  window.__rootsStale.push(event.detail);
                });
                """);

        dev.roots.browser.pages.slow.Page.hold();
        try {
            browser.findElement(By.id("slow-link")).click();
            browser.findElement(By.id("fast-link")).click();
            wait.until(driver -> URI.create(driver.getCurrentUrl()).getPath().equals("/fast"));
            wait.until(driver -> driver.getTitle().equals("Roots fast fixture"));
        } finally {
            dev.roots.browser.pages.slow.Page.release();
        }

        wait.until(ignored -> Boolean.TRUE.equals(script.executeScript("""
                return window.__rootsStale.some(event => event.kind === 'navigation');
                """)));
        wait.until(ignored -> application.runtimeSnapshot().liveViews() == 1);
        assertEquals("same-document", script.executeScript("return window.__rootsRaceMarker"));
        assertEquals("Fast page", browser.findElement(By.id("fast-page")).getText());
        assertTrue(browser.findElements(By.id("slow-page")).isEmpty());

        browser.findElement(By.id("fast-home")).click();
        wait.until(driver -> URI.create(driver.getCurrentUrl()).getPath().equals("/"));
        wait.until(driver -> driver.getTitle().equals("Roots browser fixture"));
        script.executeScript("""
                window.__rootsStale = [];
                window.__rootsRaceMarker = 'old-action-cannot-win';
                """);

        dev.roots.browser.pages.Page.holdOptimisticActions();
        try {
            browser.findElement(By.id("counter")).click();
            wait.until(ignored -> browser.findElement(By.id("counter")).getText().equals("Count pending"));
            browser.findElement(By.id("fast-link")).click();
            wait.until(driver -> URI.create(driver.getCurrentUrl()).getPath().equals("/fast"));
            wait.until(driver -> driver.getTitle().equals("Roots fast fixture"));
            script.executeScript("""
                    document.getElementById('roots').setAttribute('aria-busy', 'new-view-owned');
                    """);
        } finally {
            dev.roots.browser.pages.Page.releaseOptimisticActions();
        }

        assertTrue(dev.roots.browser.pages.Page.awaitOptimisticActionCompletion(),
                "the held action should complete after the test releases it");
        wait.until(ignored -> Boolean.TRUE.equals(script.executeScript("""
                return window.__rootsStale.some(event => event.kind === 'action');
                """)));
        wait.until(ignored -> application.runtimeSnapshot().liveViews() == 1);
        assertEquals("old-action-cannot-win", script.executeScript("return window.__rootsRaceMarker"));
        assertEquals("Fast page", browser.findElement(By.id("fast-page")).getText());
        assertEquals("new-view-owned",
                browser.findElement(By.id("roots")).getAttribute("aria-busy"),
                "old-view pending cleanup must not mutate the newer root");
        assertTrue(browser.findElements(By.id("counter")).isEmpty());
    }

    @Test
    void isolatesLateValidationAndQueuedActionsFromANewerView() throws Exception {
        browser.get(application.uri().toString());
        wait.until(driver -> driver.getTitle().equals("Roots browser fixture"));
        var script = (JavascriptExecutor) browser;
        script.executeScript("""
                window.__rootsFailureRaceMarker = 'late-validation-safe';
                window.__rootsStale = [];
                window.__rootsValidationRaces = [];
                window.__rootsFailureRaces = [];
                window.addEventListener('roots:stale', event => window.__rootsStale.push(event.detail));
                window.addEventListener('roots:validation', event => {
                  window.__rootsValidationRaces.push(event.detail);
                });
                window.addEventListener('roots:error', event => {
                  window.__rootsFailureRaces.push(String(event.detail));
                });
                """);

        browser.findElement(By.id("validation-email")).sendKeys("invalid");
        dev.roots.browser.pages.Page.holdValidationActions();
        try {
            browser.findElement(By.id("validation-submit")).click();
            assertTrue(dev.roots.browser.pages.Page.awaitValidationActionStart(),
                    "the validation action should reach its deterministic server gate");
            browser.findElement(By.id("fast-link")).click();
            wait.until(driver -> URI.create(driver.getCurrentUrl()).getPath().equals("/fast"));
            wait.until(driver -> driver.getTitle().equals("Roots fast fixture"));
        } finally {
            dev.roots.browser.pages.Page.releaseValidationActions();
        }

        assertTrue(dev.roots.browser.pages.Page.awaitValidationActionCompletion(),
                "the held validation action should complete after release");
        wait.until(ignored -> Boolean.TRUE.equals(script.executeScript("""
                return window.__rootsStale.some(event => event.kind === 'action');
                """)));
        assertEquals(List.of(), script.executeScript("return window.__rootsValidationRaces"));
        assertEquals(List.of(), script.executeScript("return window.__rootsFailureRaces"));
        assertEquals("late-validation-safe",
                script.executeScript("return window.__rootsFailureRaceMarker"));
        assertEquals("Fast page", browser.findElement(By.id("fast-page")).getText());

        browser.findElement(By.id("fast-home")).click();
        wait.until(driver -> driver.getTitle().equals("Roots browser fixture"));
        browser.findElement(By.id("validation-email")).sendKeys("invalid");
        script.executeScript("window.__rootsStale = []");
        var validationInvocations = dev.roots.browser.pages.Page.validationActionInvocations();

        dev.roots.browser.pages.Page.holdOptimisticActions();
        try {
            browser.findElement(By.id("counter")).click();
            wait.until(ignored -> browser.findElement(By.id("counter")).getText().equals("Count pending"));
            browser.findElement(By.id("validation-submit")).click();
            browser.findElement(By.id("fast-link")).click();
            wait.until(driver -> driver.getTitle().equals("Roots fast fixture"));
        } finally {
            dev.roots.browser.pages.Page.releaseOptimisticActions();
        }

        assertTrue(dev.roots.browser.pages.Page.awaitOptimisticActionCompletion());
        wait.until(ignored -> ((Number) script.executeScript("""
                return window.__rootsStale.filter(event => event.kind === 'action').length;
                """)).longValue() >= 2L);
        assertEquals(validationInvocations,
                dev.roots.browser.pages.Page.validationActionInvocations(),
                "an action queued on the old page must never be rebound to the new view");
        assertEquals(List.of(), script.executeScript("return window.__rootsValidationRaces"));
        assertEquals(List.of(), script.executeScript("return window.__rootsFailureRaces"));
        assertEquals("Fast page", browser.findElement(By.id("fast-page")).getText());
        wait.until(ignored -> application.runtimeSnapshot().liveViews() == 1);
    }

    @Test
    void supportsFragmentsClientRedirectsAndHistoryScrollRestoration() {
        browser.get(application.uri().toString());
        wait.until(driver -> driver.getTitle().equals("Roots browser fixture"));
        var script = (JavascriptExecutor) browser;
        var initialView = browser.findElement(By.id("roots")).getAttribute("data-roots-view");
        script.executeScript("""
                window.__rootsHistoryMarker = 'same-document-history';
                window.__rootsHistoryNavigations = [];
                window.addEventListener('roots:navigate', event => {
                  window.__rootsHistoryNavigations.push(event.detail);
                });
                """);

        browser.findElement(By.id("home-fragment-link")).click();
        wait.until(driver -> "home-fragment-target".equals(
                URI.create(driver.getCurrentUrl()).getFragment()));
        wait.until(ignored -> ((Number) script.executeScript("return window.scrollY")).longValue() > 500L);
        assertEquals(initialView,
                browser.findElement(By.id("roots")).getAttribute("data-roots-view"),
                "a same-resource fragment must not allocate a replacement live view");
        assertEquals(List.of(), script.executeScript("return window.__rootsHistoryNavigations"));
        assertEquals(1, application.runtimeSnapshot().liveViews());

        script.executeScript("history.back()");
        wait.until(driver -> URI.create(driver.getCurrentUrl()).getFragment() == null);
        wait.until(ignored -> ((Number) script.executeScript("return window.scrollY")).longValue() < 20L);
        assertEquals(initialView,
                browser.findElement(By.id("roots")).getAttribute("data-roots-view"));

        browser.findElement(By.id("fast-fragment-link")).click();
        wait.until(driver -> driver.getTitle().equals("Roots fast fixture"));
        wait.until(driver -> "fast-fragment-target".equals(
                URI.create(driver.getCurrentUrl()).getFragment()));
        wait.until(ignored -> ((Number) script.executeScript("return window.scrollY")).longValue() > 500L);
        assertEquals("same-document-history",
                script.executeScript("return window.__rootsHistoryMarker"));
        assertEquals("Fast fragment target",
                browser.findElement(By.id("fast-fragment-target")).getText());
        wait.until(ignored -> application.runtimeSnapshot().liveViews() == 1);

        script.executeScript("history.back()");
        wait.until(driver -> driver.getTitle().equals("Roots browser fixture"));
        wait.until(ignored -> ((Number) script.executeScript("return window.scrollY")).longValue() < 20L);
        script.executeScript("window.__rootsHistoryMarker = 'action-redirect-survived'");
        browser.findElement(By.id("action-fragment-redirect")).click();
        wait.until(driver -> driver.getTitle().equals("Roots fast fixture"));
        wait.until(driver -> "fast-fragment-target".equals(
                URI.create(driver.getCurrentUrl()).getFragment()));
        wait.until(ignored -> ((Number) script.executeScript("return window.scrollY")).longValue() > 500L);
        assertEquals("action-redirect-survived",
                script.executeScript("return window.__rootsHistoryMarker"),
                "an internal action redirect should use Roots client navigation");
        wait.until(ignored -> application.runtimeSnapshot().liveViews() == 1);

        script.executeScript("history.back()");
        wait.until(driver -> driver.getTitle().equals("Roots browser fixture"));
        script.executeScript("window.__rootsHistoryMarker = 'scroll-history-survived'");
        script.executeScript("window.scrollTo(0, 600)");
        wait.until(ignored -> ((Number) script.executeScript("""
                return history.state?.__rootsScroll?.y || 0;
                """)).longValue() >= 550L);
        script.executeScript("document.getElementById('fast-link').click()");
        wait.until(driver -> driver.getTitle().equals("Roots fast fixture"));
        wait.until(ignored -> ((Number) script.executeScript("return window.scrollY")).longValue() < 20L);

        script.executeScript("history.back()");
        wait.until(driver -> driver.getTitle().equals("Roots browser fixture"));
        wait.until(ignored -> {
            var scroll = ((Number) script.executeScript("return window.scrollY")).longValue();
            return scroll >= 550L && scroll <= 650L;
        });
        assertEquals("scroll-history-survived",
                script.executeScript("return window.__rootsHistoryMarker"));
        wait.until(ignored -> application.runtimeSnapshot().liveViews() == 1);
    }

    @Test
    void shippedExamplesPassWcag22AaAndBestPracticeAudits() {
        try (var chat = Roots.start(RootsConfig.forApplication(dev.roots.examples.chat.Application.class)
                .port(0)
                .development(false)
                .build())) {
            browser.get(chat.uri().toString());
            wait.until(driver -> driver.findElements(By.cssSelector(".chat-panel")).size() == 1);
            assertAccessible("chat example");
        }

        try (var enterprise = Roots.start(RootsConfig.forApplication(com.acme.Application.class)
                .port(0)
                .development(false)
                .cache(RootsCache.inMemory(2_000))
                .authorize("initialized-view", request -> request.session().get("visits").isPresent()
                        ? AuthorizationPolicy.allow()
                        : AuthorizationPolicy.deny(Response.json(403, "{\"error\":\"Forbidden\"}")))
                .build())) {
            browser.get(enterprise.uri().toString());
            wait.until(driver -> driver.findElements(By.cssSelector(".overview-page")).size() == 1);
            assertAccessible("enterprise overview example");

            browser.get(enterprise.uri().resolve("customers").toString());
            wait.until(driver -> driver.findElements(By.cssSelector(".customers-page")).size() == 1);
            assertAccessible("enterprise customers example");
        }
    }

    @Test
    void runsLiveActionsAndClientNavigationInARealBrowser() {
        browser.get(application.uri().toString());
        wait.until(driver -> driver.getTitle().equals("Roots browser fixture"));

        var root = browser.findElement(By.id("roots"));
        var initialView = root.getAttribute("data-roots-view");
        assertEquals("1", root.getAttribute("data-roots-revision"));
        assertEquals(List.of(application.uri().resolve("base.css").toString()), stylesheetUrls());

        var script = (JavascriptExecutor) browser;
        script.executeScript("""
                window.__rootsCounter = document.getElementById('counter');
                window.__rootsMarker = 'client-navigation-survived';
                window.__rootsErrors = [];
                window.__rootsValidations = [];
                window.__rootsRenders = [];
                window.__rootsScopedShell = document.getElementById('scoped-shell');
                window.addEventListener('roots:error', event => {
                  window.__rootsErrors.push(String(event.detail));
                });
                window.addEventListener('roots:validation', event => {
                  window.__rootsValidations.push(event.detail);
                });
                window.addEventListener('roots:render', event => {
                  window.__rootsRenders.push(event.detail);
                });
                """);

        dev.roots.browser.pages.Page.holdOptimisticActions();
        try {
            browser.findElement(By.id("counter")).click();
            wait.until(ignored -> Boolean.TRUE.equals(script.executeScript("""
                    const button = document.getElementById('counter');
                    const scope = document.getElementById('counter-scope');
                    return button.textContent === 'Count pending' && button.disabled
                      && scope.dataset.rootsPending === 'true'
                      && scope.getAttribute('aria-busy') === 'true'
                      && !document.getElementById('outer-pending-scope').hasAttribute('data-roots-pending')
                      && document.getElementById('roots').getAttribute('aria-busy') === 'true'
                      && document.documentElement.dataset.rootsPending === 'true';
                    """)));
        } finally {
            dev.roots.browser.pages.Page.releaseOptimisticActions();
        }
        wait.until(driver -> driver.findElement(By.id("counter")).getText().equals("Count 1"));
        assertTrue(browser.findElement(By.id("counter")).isEnabled());
        assertEquals("click:button=0", browser.findElement(By.id("event-result")).getText());
        assertNull(browser.findElement(By.id("counter-scope")).getAttribute("data-roots-pending"));
        assertNull(browser.findElement(By.id("counter-scope")).getAttribute("aria-busy"));
        assertNull(browser.findElement(By.id("roots")).getAttribute("aria-busy"));
        assertNull(script.executeScript("return document.documentElement.dataset.rootsPending"));

        assertEquals("2", browser.findElement(By.id("roots")).getAttribute("data-roots-revision"));
        assertEquals(Boolean.TRUE, script.executeScript(
                "return window.__rootsCounter === document.getElementById('counter')"));
        assertEquals(List.of(), script.executeScript("return window.__rootsErrors"));

        script.executeScript("window.__rootsRenders = []");
        browser.findElement(By.id("scoped-counter")).click();
        wait.until(driver -> driver.findElement(By.id("scoped-counter")).getText().equals("Scoped 1"));
        assertEquals("3", browser.findElement(By.id("roots")).getAttribute("data-roots-revision"));
        assertEquals("c0", script.executeScript("return window.__rootsRenders.at(-1).scope"));
        assertEquals(Boolean.TRUE, script.executeScript("return window.__rootsRenders.at(-1).domChanged"));
        assertEquals(Boolean.TRUE, script.executeScript(
                "return window.__rootsScopedShell === document.getElementById('scoped-shell')"));
        assertEquals("Outside scoped boundary", browser.findElement(By.id("scoped-sentinel")).getText());

        dev.roots.browser.pages.Page.holdOptimisticActions();
        try {
            browser.findElement(By.id("optimistic-failure")).click();
            wait.until(ignored -> Boolean.TRUE.equals(script.executeScript("""
                    const button = document.getElementById('optimistic-failure');
                    const value = document.getElementById('optimistic-value');
                    const scope = document.getElementById('failure-scope');
                    return button.textContent === 'Working' && button.disabled
                      && value.value === 'temporary'
                      && scope.dataset.rootsPending === 'true'
                      && scope.getAttribute('aria-busy') === 'true';
                    """)));
        } finally {
            dev.roots.browser.pages.Page.releaseOptimisticActions();
        }
        wait.until(driver -> driver.findElement(By.id("optimistic-failure")).getText().equals("Fail safely"));
        assertTrue(browser.findElement(By.id("optimistic-failure")).isEnabled());
        assertEquals("original", browser.findElement(By.id("optimistic-value")).getAttribute("value"));
        wait.until(ignored -> ((List<?>) script.executeScript("return window.__rootsErrors")).size() == 1);
        assertNull(browser.findElement(By.id("failure-scope")).getAttribute("data-roots-pending"));
        assertEquals("false", browser.findElement(By.id("failure-scope")).getAttribute("aria-busy"));
        assertNull(script.executeScript("return document.documentElement.dataset.rootsPending"));
        script.executeScript("window.__rootsErrors = []");

        var validationEmail = browser.findElement(By.id("validation-email"));
        validationEmail.sendKeys("invalid");
        browser.findElement(By.id("validation-submit")).click();
        wait.until(driver -> driver.findElement(By.cssSelector("[data-roots-validation-for=\"email\"]"))
                .getText().contains("Enter a valid email."));
        var emailError = browser.findElement(By.cssSelector("[data-roots-validation-for=\"email\"]"));
        assertEquals("true", validationEmail.getAttribute("aria-invalid"));
        assertTrue(validationEmail.getAttribute("aria-describedby").contains("email-help"));
        assertTrue(emailError.getAttribute("id").startsWith("roots-validation-"));
        assertTrue(validationEmail.getAttribute("aria-describedby").contains(emailError.getAttribute("id")));
        assertTrue(emailError.getText().contains("<script>unsafe</script>"));
        assertTrue(emailError.findElements(By.cssSelector("script")).isEmpty());
        assertEquals("Check the highlighted fields", browser.findElement(By.id("validation-summary")).getText());
        assertEquals("3", browser.findElement(By.id("roots")).getAttribute("data-roots-revision"));
        assertEquals(1L, script.executeScript("return window.__rootsValidations.length"));
        assertEquals(List.of(), script.executeScript("return window.__rootsErrors"));

        validationEmail.sendKeys("x");
        wait.until(ignored -> Boolean.TRUE.equals(script.executeScript(
                "return document.querySelector('[data-roots-validation-for=\"email\"]').hidden")));
        assertEquals("false", validationEmail.getAttribute("aria-invalid"));
        assertEquals("email-help", validationEmail.getAttribute("aria-describedby"));
        assertEquals("", emailError.getText());
        assertEquals(Boolean.FALSE, script.executeScript("return arguments[0].hasAttribute('id')", emailError));
        validationEmail.clear();
        validationEmail.sendKeys("valid@example.com");
        browser.findElement(By.id("validation-submit")).click();
        wait.until(driver -> driver.findElement(By.id("validation-status")).getText()
                .equals("Accepted valid@example.com"));
        assertEquals("4", browser.findElement(By.id("roots")).getAttribute("data-roots-revision"));

        browser.findElement(By.id("event-input")).sendKeys("A");
        wait.until(driver -> driver.findElement(By.id("event-result")).getText().equals("input:A"));
        assertEquals("5", browser.findElement(By.id("roots")).getAttribute("data-roots-revision"));

        browser.findElement(By.id("event-key")).click();
        wait.until(driver -> driver.findElement(By.id("event-result")).getText().equals("focus:user"));
        assertEquals("6", browser.findElement(By.id("roots")).getAttribute("data-roots-revision"));

        script.executeScript("""
                document.getElementById('event-key').dispatchEvent(new KeyboardEvent('keydown', {
                  key: 'Enter', code: 'Enter', ctrlKey: true, bubbles: true
                }));
                """);
        wait.until(driver -> driver.findElement(By.id("event-result")).getText()
                .equals("keydown:Enter:Enter:control=true"));
        assertEquals("7", browser.findElement(By.id("roots")).getAttribute("data-roots-revision"));

        script.executeScript("""
                document.getElementById('event-pointer').dispatchEvent(new PointerEvent('pointerdown', {
                  button: 2, clientX: 15, clientY: 25, bubbles: true
                }));
                """);
        wait.until(driver -> driver.findElement(By.id("event-result")).getText()
                .equals("pointerdown:button=2:x=15:y=25"));
        assertEquals("8", browser.findElement(By.id("roots")).getAttribute("data-roots-revision"));
        assertEquals(List.of(), script.executeScript("return window.__rootsErrors"));

        browser.findElement(By.id("upload-file")).sendKeys(
                Path.of("src/test/resources/upload-fixture.txt").toAbsolutePath().toString()
        );
        browser.findElement(By.id("upload-submit")).click();
        wait.until(driver -> driver.findElement(By.id("upload-result")).getText()
                .equals("upload-fixture.txt:browser-file-content"));
        assertEquals("9", browser.findElement(By.id("roots")).getAttribute("data-roots-revision"));
        assertEquals(List.of(), script.executeScript("return window.__rootsErrors"));

        assertTrue(browser.findElements(By.cssSelector("[data-roots-portal-host]")).isEmpty());
        browser.findElement(By.id("open-overlay")).click();
        wait.until(driver -> driver.findElements(By.cssSelector(
                "body > [data-roots-portal-host=\"browser-overlay\"]")).size() == 1);
        var portalHost = browser.findElement(By.cssSelector("[data-roots-portal-host=\"browser-overlay\"]"));
        assertTrue(portalHost.findElement(By.cssSelector("dialog")).getAttribute("open") != null);
        wait.until(driver -> driver.switchTo().activeElement().getAttribute("id").equals("portal-name"));
        assertEquals("10", browser.findElement(By.id("roots")).getAttribute("data-roots-revision"));
        assertEquals("pointerdown:button=2:x=15:y=25", browser.findElement(By.id("event-result")).getText());
        assertTrue(browser.findElement(By.id("roots"))
                .findElements(By.cssSelector("template[data-roots-portal]")).isEmpty());

        var portalName = portalHost.findElement(By.id("portal-name"));
        portalName.sendKeys("Ada");
        portalHost.findElement(By.id("portal-save")).click();
        wait.until(driver -> driver.findElement(By.id("portal-greeting")).getText().equals("Hello, Ada"));
        assertEquals(Boolean.TRUE, script.executeScript(
                "return arguments[0] === document.querySelector('[data-roots-portal-host=\"browser-overlay\"]')",
                portalHost));
        assertEquals("11", browser.findElement(By.id("roots")).getAttribute("data-roots-revision"));
        assertEquals("portal-name", browser.switchTo().activeElement().getAttribute("id"));

        var portalDialog = portalHost.findElement(By.cssSelector("dialog"));
        dev.roots.browser.pages.Page.holdOptimisticActions();
        try {
            portalHost.findElement(By.id("portal-close")).click();
            wait.until(ignored -> Boolean.TRUE.equals(script.executeScript("""
                    const dialog = arguments[0];
                    const scope = document.getElementById('portal-pending-scope');
                    return dialog.hidden && scope.dataset.rootsPending === 'true'
                      && scope.getAttribute('aria-busy') === 'true';
                    """, portalDialog)));
        } finally {
            dev.roots.browser.pages.Page.releaseOptimisticActions();
        }
        wait.until(driver -> driver.findElements(By.cssSelector("[data-roots-portal-host]")).isEmpty());
        assertEquals("12", browser.findElement(By.id("roots")).getAttribute("data-roots-revision"));
        assertEquals(List.of(), script.executeScript("return window.__rootsErrors"));

        script.executeScript("""
                window.__rootsNavigationTransitionCalls = 0;
                window.__rootsNavigationOriginalTransition = document.startViewTransition;
                window.__rootsNavigationOriginalMatchMedia = window.matchMedia;
                window.matchMedia = query => query === '(prefers-reduced-motion: reduce)'
                  ? { matches: false }
                  : window.__rootsNavigationOriginalMatchMedia(query);
                document.startViewTransition = update => {
                  window.__rootsNavigationTransitionCalls++;
                  return { updateCallbackDone: Promise.resolve().then(update) };
                };
                """);
        browser.findElement(By.id("themed-link")).click();
        wait.until(driver -> driver.getCurrentUrl().endsWith("/themed"));
        wait.until(driver -> driver.getTitle().equals("Roots themed fixture"));
        wait.until(driver -> stylesheetUrls().equals(List.of(application.uri().resolve("theme.css").toString())));
        wait.until(driver -> driver.findElement(By.cssSelector("link[rel='canonical']"))
                .getAttribute("href").equals(application.uri().resolve("themed").toString()));
        assertEquals("index, follow", browser.findElement(By.cssSelector("meta[name='robots']"))
                .getAttribute("content"));
        assertEquals("#114477", browser.findElement(By.cssSelector("meta[name='theme-color']"))
                .getAttribute("content"));
        assertEquals("Roots themed fixture", browser.findElement(By.cssSelector("meta[property='og:title']"))
                .getAttribute("content"));
        assertEquals("Theme preview", browser.findElement(By.cssSelector("meta[property='og:image:alt']"))
                .getAttribute("content"));
        wait.until(driver -> driver.findElement(By.id("themed-portal")).getText().equals("Themed portal mounted"));
        assertEquals("themed-status", browser.findElement(By.cssSelector("[data-roots-portal-host]"))
                .getAttribute("data-roots-portal-host"));
        assertEquals(1L, script.executeScript("return window.__rootsNavigationTransitionCalls"));

        browser.findElement(By.id("head-update")).click();
        wait.until(driver -> driver.findElement(By.cssSelector("meta[name='theme-color']"))
                .getAttribute("content").equals("#553399"));
        assertEquals("noindex, nofollow", browser.findElement(By.cssSelector("meta[name='robots']"))
                .getAttribute("content"));
        assertEquals(application.uri().resolve("themed/updated").toString(),
                browser.findElement(By.cssSelector("link[rel='canonical']")).getAttribute("href"));
        assertEquals("Updated theme preview",
                browser.findElement(By.cssSelector("meta[property='og:image:alt']")).getAttribute("content"));

        assertEquals("client-navigation-survived", script.executeScript("return window.__rootsMarker"));
        assertNotEquals(initialView, browser.findElement(By.id("roots")).getAttribute("data-roots-view"));
        wait.until(ignored -> application.runtimeSnapshot().liveViews() == 1);
        assertFalse(browser.getPageSource().contains("Count 1"));
        assertEquals(List.of(), script.executeScript("return window.__rootsErrors"));

        browser.findElement(By.id("home-link")).click();
        wait.until(driver -> URI.create(driver.getCurrentUrl()).getPath().equals("/"));
        wait.until(driver -> driver.findElement(By.id("counter")).getText().equals("Count 0"));
        wait.until(driver -> stylesheetUrls().equals(List.of(application.uri().resolve("base.css").toString())));
        wait.until(driver -> driver.findElements(By.cssSelector("link[rel='canonical']")).isEmpty());
        assertEquals("index, follow", browser.findElement(By.cssSelector("meta[name='robots']"))
                .getAttribute("content"));
        assertEquals("#0b253f", browser.findElement(By.cssSelector("meta[name='theme-color']"))
                .getAttribute("content"));
        assertEquals("Roots Browser", browser.findElement(By.cssSelector("meta[property='og:site_name']"))
                .getAttribute("content"));
        assertTrue(browser.findElements(By.cssSelector("meta[property='og:image']")).isEmpty());
        assertTrue(browser.findElements(By.cssSelector("meta[property='og:image:alt']")).isEmpty());
        assertTrue(browser.findElements(By.cssSelector("meta[property='og:type']")).isEmpty());
        wait.until(driver -> driver.findElements(By.cssSelector("[data-roots-portal-host]")).isEmpty());
        assertEquals(2L, script.executeScript("return window.__rootsNavigationTransitionCalls"));
        script.executeScript("""
                document.startViewTransition = window.__rootsNavigationOriginalTransition;
                window.matchMedia = window.__rootsNavigationOriginalMatchMedia;
                """);

        script.executeScript("""
                window.__rootsTransitionCalls = 0;
                window.__rootsOriginalStartViewTransition = document.startViewTransition;
                window.__rootsOriginalMatchMedia = window.matchMedia;
                window.matchMedia = query => query === '(prefers-reduced-motion: reduce)'
                  ? { matches: false }
                  : window.__rootsOriginalMatchMedia(query);
                document.startViewTransition = update => {
                  window.__rootsTransitionCalls++;
                  const updateCallbackDone = Promise.resolve().then(update);
                  return { updateCallbackDone };
                };
                """);
        browser.findElement(By.id("transition-action")).click();
        wait.until(driver -> driver.findElement(By.id("transition-action")).getText().equals("Transition 1"));
        assertEquals(1L, script.executeScript("return window.__rootsTransitionCalls"));
        assertEquals("2", browser.findElement(By.id("roots")).getAttribute("data-roots-revision"));

        script.executeScript("document.startViewTransition = undefined");
        browser.findElement(By.id("transition-action")).click();
        wait.until(driver -> driver.findElement(By.id("transition-action")).getText().equals("Transition 2"));
        assertEquals(1L, script.executeScript("return window.__rootsTransitionCalls"));
        assertEquals("3", browser.findElement(By.id("roots")).getAttribute("data-roots-revision"));
        script.executeScript("""
                document.startViewTransition = update => {
                  window.__rootsTransitionCalls++;
                  throw new Error('simulated transition startup failure');
                };
                """);
        browser.findElement(By.id("transition-action")).click();
        wait.until(driver -> driver.findElement(By.id("transition-action")).getText().equals("Transition 3"));
        assertEquals(2L, script.executeScript("return window.__rootsTransitionCalls"));
        assertEquals("4", browser.findElement(By.id("roots")).getAttribute("data-roots-revision"));
        script.executeScript("""
                document.startViewTransition = update => {
                  window.__rootsTransitionCalls++;
                  return { updateCallbackDone: Promise.resolve().then(update) };
                };
                window.matchMedia = query => query === '(prefers-reduced-motion: reduce)'
                  ? { matches: true }
                  : window.__rootsOriginalMatchMedia(query);
                """);
        browser.findElement(By.id("transition-action")).click();
        wait.until(driver -> driver.findElement(By.id("transition-action")).getText().equals("Transition 4"));
        assertEquals(2L, script.executeScript("return window.__rootsTransitionCalls"));
        assertEquals("5", browser.findElement(By.id("roots")).getAttribute("data-roots-revision"));
        script.executeScript("""
                window.matchMedia = query => query === '(prefers-reduced-motion: reduce)'
                  ? { matches: false }
                  : window.__rootsOriginalMatchMedia(query);
                """);
        browser.findElement(By.id("transition-no-dom")).click();
        wait.until(driver -> driver.findElement(By.id("roots")).getAttribute("data-roots-revision").equals("6"));
        assertEquals(2L, script.executeScript("return window.__rootsTransitionCalls"));
        script.executeScript("""
                document.startViewTransition = window.__rootsOriginalStartViewTransition;
                window.matchMedia = window.__rootsOriginalMatchMedia;
                """);

        assertTrue(application.runtimeSnapshot().handledRequests() >= 5);
        assertEquals(1, application.runtimeSnapshot().liveViews());
        assertEquals(List.of(), script.executeScript("return window.__rootsErrors"));
    }

    protected final void verifyReconnectsAndAppliesAnUpdateQueuedDuringANetworkOutage(
            Runnable enableNetworkEmulation,
            Consumer<Boolean> setNetworkOffline
    ) {
        browser.get(application.uri().toString());
        wait.until(driver -> driver.findElement(By.id("roots"))
                .getAttribute("data-roots-connection").equals("connected"));

        var script = (JavascriptExecutor) browser;
        script.executeScript("""
                window.__rootsConnections = [];
                window.addEventListener('roots:connection', event => {
                  window.__rootsConnections.push(event.detail.state);
                });
                """);
        enableNetworkEmulation.run();
        try {
            setNetworkOffline.accept(true);
            dev.roots.browser.pages.Page.pushBackgroundUpdate();
            wait.until(ignored -> "reconnecting".equals(script.executeScript(
                    "return document.getElementById('roots').dataset.rootsConnection")));
        } finally {
            setNetworkOffline.accept(false);
        }

        new WebDriverWait(browser, Duration.ofSeconds(15)).until(driver ->
                driver.findElement(By.id("background-result")).getText().equals("Background 1")
                        && driver.findElement(By.id("roots"))
                        .getAttribute("data-roots-connection").equals("connected"));
        @SuppressWarnings("unchecked")
        var states = (List<String>) script.executeScript("return window.__rootsConnections");
        assertTrue(states.contains("reconnecting"), states.toString());
        assertEquals("connected", states.getLast());
        assertEquals(1, application.runtimeSnapshot().liveViews());
    }

    private List<String> stylesheetUrls() {
        return browser.findElements(By.cssSelector("link[data-roots-style]"))
                .stream()
                .map(element -> element.getAttribute("href"))
                .toList();
    }

    private void assertAccessible(String state) {
        var results = new AxeBuilder()
                .withTags(List.of("wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa", "best-practice"))
                .analyze(browser);
        assertFalse(results.isErrored(), () -> state + ": axe failed: " + results.getErrorMessage());
        assertTrue(results.getViolations().isEmpty(),
                () -> state + " has accessibility violations:\n" + auditDetails(results.getViolations()));
        assertTrue(results.getIncomplete().isEmpty(),
                () -> state + " has accessibility checks requiring review:\n" + auditDetails(results.getIncomplete()));
    }

    private static String auditDetails(List<Rule> rules) {
        return rules.stream().map(rule -> rule.getId() + " [" + rule.getImpact() + "] " + rule.getHelp()
                        + "\n  " + rule.getHelpUrl()
                        + rule.getNodes().stream()
                                .map(node -> "\n  target=" + node.getTarget()
                                        + "\n  html=" + node.getHtml()
                                        + "\n  " + node.getFailureSummary())
                                .collect(Collectors.joining()))
                .collect(Collectors.joining("\n"));
    }
}
