package com.chaplin.roots.browser;

import com.deque.html.axecore.results.Rule;
import com.deque.html.axecore.selenium.AxeBuilder;
import com.chaplin.roots.Roots;
import com.chaplin.roots.RootsCache;
import com.chaplin.roots.RootsConfig;
import com.chaplin.roots.RunningApplication;
import com.chaplin.roots.AuthorizationPolicy;
import com.chaplin.roots.Response;
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
                .mapException(com.chaplin.roots.browser.pages.outcomes.Page.MappedFailure.class,
                        (request, failure) -> Response.json(422, "{\"error\":\"mapped failure after write\"}"))
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
    void widgetsPreserveDomAcrossActionsSseAndReorderAndDisposeBeforeNavigation() {
        var script = (JavascriptExecutor) browser;
        var probe = java.util.UUID.randomUUID().toString();
        browser.get(application.uri().resolve("widgets?probe=" + probe).toString());
        wait.until(driver -> driver.findElements(By.id("widget-second-editor")).size() == 1);
        script.executeScript("""
                window.firstWidget = document.getElementById('widget-first');
                window.firstEditor = document.getElementById('widget-first-editor');
                window.firstEditor.textContent = 'Unsent widget draft';
                window.firstEditor.dispatchEvent(new Event('input', {bubbles:true}));
                """);
        browser.findElement(By.id("widget-update")).click();
        wait.until(driver -> driver.findElement(By.id("widget-first-value")).getText().equals("1"));
        com.chaplin.roots.browser.pages.widgets.Page.UPDATES.get(probe).run();
        wait.until(driver -> driver.findElement(By.id("widget-first-value")).getText().equals("2"));
        browser.findElement(By.id("widget-reverse")).click();
        wait.until(ignored -> Boolean.TRUE.equals(script.executeScript(
                "return document.getElementById('widget-list').firstElementChild.id === 'widget-second'")));
        assertEquals(Boolean.TRUE, script.executeScript("return firstWidget === document.getElementById('widget-first') && firstEditor === document.getElementById('widget-first-editor')"));
        assertEquals("Unsent widget draft", browser.findElement(By.id("widget-first-editor")).getText());
        browser.findElement(By.id("widget-toggle")).click();
        wait.until(driver -> driver.findElements(By.id("widget-first")).isEmpty());
        assertEquals(List.of("abort", "destroy"), script.executeScript(
                "return widgetTrace.filter(e => e.id==='widget-first' && ['abort','destroy'].includes(e.event)).map(e=>e.event)"));
        assertEquals(Boolean.TRUE, script.executeScript("return widgetTrace.find(e=>e.id==='widget-first' && e.event==='destroy').connected"));
        browser.findElement(By.id("widget-fresh")).click();
        wait.until(ignored -> ((Number) script.executeScript("return widgetTrace.filter(e=>e.event==='mount').length")).intValue() == 4);
        assertEquals(2, ((Number) script.executeScript("return widgetTrace.filter(e=>e.event==='destroy').length")).intValue());
    }

    @Test
    void widgetsCoalesceAsyncUpdatesAndCleanUpLateMounts() {
        var script = (JavascriptExecutor) browser;
        browser.get(application.uri().resolve("widgets").toString());
        wait.until(driver -> driver.findElements(By.id("widget-first-editor")).size() == 1);
        browser.findElement(By.id("widget-slow")).click();
        wait.until(ignored -> Boolean.TRUE.equals(script.executeScript("return typeof releaseWidgetUpdate === 'function'")));
        browser.findElement(By.id("widget-update")).click();
        browser.findElement(By.id("widget-update")).click();
        wait.until(driver -> driver.findElement(By.id("widget-server-value")).getText().equals("3"));
        script.executeScript("releaseWidgetUpdate()");
        wait.until(driver -> driver.findElement(By.id("widget-first-value")).getText().equals("3"));
        assertEquals(List.of("1", "3"), script.executeScript("return widgetTrace.filter(e=>e.id==='widget-first' && e.event==='update').map(e=>e.value)"));

        browser.get(application.uri().resolve("widgets?wait=true").toString());
        wait.until(ignored -> Boolean.TRUE.equals(script.executeScript("return typeof releaseWidgetMount === 'function'")));
        browser.findElement(By.id("widget-toggle")).click();
        wait.until(driver -> driver.findElements(By.id("widget-first")).isEmpty());
        script.executeScript("releaseWidgetMount()");
        wait.until(ignored -> ((Number) script.executeScript("return widgetTrace.filter(e=>e.id==='widget-first' && e.event==='destroy').length")).intValue() == 1);
        assertEquals(Boolean.FALSE, script.executeScript("return widgetTrace.find(e=>e.id==='widget-first' && e.event==='destroy').connected"));
        assertTrue(browser.findElements(By.id("widget-first-editor")).isEmpty());
    }

    @Test
    void widgetFailuresAndViewExpiryPreserveCustomEditorDrafts() {
        var script = (JavascriptExecutor) browser;
        browser.get(application.uri().resolve("widgets").toString());
        wait.until(driver -> driver.findElements(By.id("widget-first-editor")).size() == 1);
        var view = browser.findElement(By.id("roots")).getDomAttribute("data-roots-view");
        script.executeScript("""
                window.widgetErrors = [];
                window.addEventListener('roots:widget-error', e => widgetErrors.push(e.detail.phase));
                const editor = document.getElementById('widget-first-editor');
                editor.textContent = 'Keep this custom editor draft';
                editor.dispatchEvent(new Event('input', {bubbles:true}));
                """);
        browser.findElement(By.id("widget-fail")).click();
        wait.until(ignored -> Boolean.TRUE.equals(script.executeScript("return widgetErrors.includes('update')")));
        assertEquals("Keep this custom editor draft", browser.findElement(By.id("widget-first-editor")).getText());
        assertTrue(browser.findElement(By.id("widget-first")).getText().contains("Keep a copy"));
        script.executeScript("""
                const realFetch = window.fetch.bind(window);
                window.fetch = (url, options) => String(url).includes('/_roots/action')
                  ? Promise.resolve(new Response('', {status:409})) : realFetch(url, options);
                """);
        browser.findElement(By.id("widget-update")).click();
        wait.until(driver -> "expired".equals(driver.findElement(By.id("roots")).getDomAttribute("data-roots-action-state")));
        assertEquals(view, browser.findElement(By.id("roots")).getDomAttribute("data-roots-view"));
        assertEquals("Keep this custom editor draft", browser.findElement(By.id("widget-first-editor")).getText());
    }

    @Test
    void widgetsReceiveScopedPropsAndRemountWhenScopedHostTagChanges() {
        var script = (JavascriptExecutor) browser;
        browser.get(application.uri().resolve("widgets?scoped=true").toString());
        wait.until(driver -> driver.findElements(By.id("widget-scoped-editor")).size() == 1);
        script.executeScript("window.scopedEditor=document.getElementById('widget-scoped-editor')");
        browser.findElement(By.id("widget-scoped-update")).click();
        wait.until(driver -> driver.findElement(By.id("widget-scoped-value")).getText().equals("1"));
        assertEquals(Boolean.TRUE, script.executeScript("return scopedEditor===document.getElementById('widget-scoped-editor')"));
        browser.findElement(By.id("widget-scoped-tag")).click();
        wait.until(ignored -> ((Number) script.executeScript("return widgetTrace.filter(e=>e.id==='widget-scoped' && e.event==='mount').length")).intValue() == 2);
        assertEquals("section", browser.findElement(By.id("widget-scoped")).getTagName());
        assertEquals("1", browser.findElement(By.id("widget-scoped-value")).getText());
    }

    @Test
    void widgetsBridgeNativeFormsAndRetainDraftThroughValidation() {
        var script = (JavascriptExecutor) browser;
        browser.get(application.uri().resolve("widgets").toString());
        wait.until(driver -> driver.findElements(By.id("widget-first-editor")).size() == 1);
        browser.findElement(By.id("widget-save")).click();
        wait.until(driver -> driver.findElement(By.tagName("body")).getText().contains("Validation failed"));
        script.executeScript("const e=document.getElementById('widget-first-editor'); e.textContent='Bridged native form'; e.dispatchEvent(new Event('input',{bubbles:true}))");
        browser.findElement(By.id("widget-update")).click();
        wait.until(driver -> driver.findElement(By.id("widget-server-value")).getText().equals("1"));
        assertEquals("Bridged native form", browser.findElement(By.id("widget-bridge")).getDomProperty("value"));
        browser.findElement(By.id("widget-save")).click();
        wait.until(driver -> driver.findElement(By.id("widget-saved")).getText().equals("Bridged native form"));
        assertEquals("Bridged native form", browser.findElement(By.id("widget-first-editor")).getText());
    }

    @Test
    void widgetsReplaceModulesHandleMissingAssetsAndCleanUpPortals() {
        var script = (JavascriptExecutor) browser;
        browser.get(application.uri().resolve("widgets?portal=true").toString());
        wait.until(driver -> driver.findElements(By.id("widget-portal-editor")).size() == 1);
        browser.findElement(By.id("widget-update")).click();
        wait.until(driver -> driver.findElement(By.id("widget-portal-value")).getText().equals("1"));
        browser.findElement(By.id("widget-module")).click();
        wait.until(ignored -> ((Number) script.executeScript("return widgetTrace.filter(e=>e.id==='widget-first' && e.event==='mount').length")).intValue() == 2);
        assertEquals(1, ((Number) script.executeScript("return widgetTrace.filter(e=>e.id==='widget-first' && e.event==='destroy').length")).intValue());
        script.executeScript("window.widgetErrors=[]; window.addEventListener('roots:widget-error', e=>widgetErrors.push(e.detail.phase))");
        browser.findElement(By.id("widget-missing")).click();
        wait.until(ignored -> Boolean.TRUE.equals(script.executeScript("return widgetErrors.includes('mount')")));
        assertEquals("Fallback 1", browser.findElement(By.id("widget-first")).getText());
        browser.findElement(By.id("widget-update")).click();
        wait.until(driver -> driver.findElement(By.id("widget-first")).getText().equals("Fallback 2"));
        browser.findElement(By.id("widget-toggle")).click();
        wait.until(driver -> driver.findElements(By.id("widget-portal")).isEmpty());
        assertEquals(List.of("abort", "destroy"), script.executeScript("return widgetTrace.filter(e=>e.id==='widget-portal' && ['abort','destroy'].includes(e.event)).map(e=>e.event)"));
        assertEquals(Boolean.TRUE, script.executeScript("return widgetTrace.find(e=>e.id==='widget-portal' && e.event==='destroy').connected"));
    }

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

        com.chaplin.roots.browser.pages.slow.Page.hold();
        try {
            browser.findElement(By.id("slow-link")).click();
            browser.findElement(By.id("fast-link")).click();
            wait.until(driver -> URI.create(driver.getCurrentUrl()).getPath().equals("/fast"));
            wait.until(driver -> driver.getTitle().equals("Roots fast fixture"));
        } finally {
            com.chaplin.roots.browser.pages.slow.Page.release();
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

        com.chaplin.roots.browser.pages.Page.holdOptimisticActions();
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
            com.chaplin.roots.browser.pages.Page.releaseOptimisticActions();
        }

        assertTrue(com.chaplin.roots.browser.pages.Page.awaitOptimisticActionCompletion(),
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
        com.chaplin.roots.browser.pages.Page.holdValidationActions();
        try {
            browser.findElement(By.id("validation-submit")).click();
            assertTrue(com.chaplin.roots.browser.pages.Page.awaitValidationActionStart(),
                    "the validation action should reach its deterministic server gate");
            browser.findElement(By.id("fast-link")).click();
            wait.until(driver -> URI.create(driver.getCurrentUrl()).getPath().equals("/fast"));
            wait.until(driver -> driver.getTitle().equals("Roots fast fixture"));
        } finally {
            com.chaplin.roots.browser.pages.Page.releaseValidationActions();
        }

        assertTrue(com.chaplin.roots.browser.pages.Page.awaitValidationActionCompletion(),
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
        var validationInvocations = com.chaplin.roots.browser.pages.Page.validationActionInvocations();

        com.chaplin.roots.browser.pages.Page.holdOptimisticActions();
        try {
            browser.findElement(By.id("counter")).click();
            wait.until(ignored -> browser.findElement(By.id("counter")).getText().equals("Count pending"));
            browser.findElement(By.id("validation-submit")).click();
            browser.findElement(By.id("fast-link")).click();
            wait.until(driver -> driver.getTitle().equals("Roots fast fixture"));
        } finally {
            com.chaplin.roots.browser.pages.Page.releaseOptimisticActions();
        }

        assertTrue(com.chaplin.roots.browser.pages.Page.awaitOptimisticActionCompletion());
        wait.until(ignored -> ((Number) script.executeScript("""
                return window.__rootsStale.filter(event => event.kind === 'action').length;
                """)).longValue() >= 2L);
        assertEquals(validationInvocations,
                com.chaplin.roots.browser.pages.Page.validationActionInvocations(),
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
    void keepsDraftControlsAcrossUnrelatedActionsAndBackgroundPatches() {
        browser.get(application.uri().resolve("forms").toString());
        var script = (JavascriptExecutor) browser;
        script.executeScript("""
                for (const [id, value] of [['draft', 'Unsaved company'], ['notes', 'Private draft']]) {
                  const control = document.getElementById(id);
                  control.value = value;
                  control.dispatchEvent(new Event('input', {bubbles:true}));
                }
                const enabled = document.getElementById('enabled');
                enabled.checked = true;
                enabled.dispatchEvent(new Event('change', {bubbles:true}));
                const choices = document.getElementById('choices');
                choices.options[1].selected = choices.options[2].selected = true;
                choices.dispatchEvent(new Event('change', {bubbles:true}));
                """);
        browser.findElement(By.id("file")).sendKeys(
                Path.of("src/test/resources/upload-fixture.txt").toAbsolutePath().toString());
        browser.findElement(By.id("update")).click();
        wait.until(driver -> driver.findElement(By.id("updates")).getText().equals("1"));
        assertDraftControls(script);
        com.chaplin.roots.browser.pages.forms.Page.push();
        wait.until(driver -> driver.findElement(By.id("updates")).getText().equals("2"));
        assertDraftControls(script);
        browser.findElement(By.id("save")).click();
        wait.until(driver -> driver.findElement(By.id("saved")).getText().equals("Unsaved company"));
        assertEquals("", browser.findElement(By.id("draft")).getDomProperty("value"));
        assertEquals("", browser.findElement(By.id("notes")).getDomProperty("value"));
        assertFalse(browser.findElement(By.id("enabled")).isSelected());
        assertEquals(0L, script.executeScript("return document.getElementById('choices').selectedOptions.length"));
        assertEquals(0L, script.executeScript("return document.getElementById('file').files.length"));
    }

    private void assertDraftControls(JavascriptExecutor script) {
        assertEquals("Unsaved company", browser.findElement(By.id("draft")).getDomProperty("value"));
        assertEquals("Private draft", browser.findElement(By.id("notes")).getDomProperty("value"));
        assertTrue(browser.findElement(By.id("enabled")).isSelected());
        assertEquals(List.of("two", "three"), script.executeScript(
                "return Array.from(document.getElementById('choices').selectedOptions, option => option.value)"));
        assertEquals(1L, script.executeScript("return document.getElementById('file').files.length"));
    }

    @Test
    void capturesQueuedInputBeforeAnOlderResponseCanOverwriteIt() {
        browser.get(application.uri().resolve("forms").toString());
        var script = (JavascriptExecutor) browser;
        script.executeScript("""
                window.__realFetch = window.fetch.bind(window);
                window.__sentQueries = [];
                window.fetch = async (url, options) => {
                  if (!String(url).includes('/_roots/action')) return window.__realFetch(url, options);
                  window.__sentQueries.push(new URLSearchParams(options.body).get('query'));
                  const response = await window.__realFetch(url, options);
                  if (window.__sentQueries.length === 1) {
                    await new Promise(resolve => window.__releaseResponse = resolve);
                  }
                  return response;
                };
                const field = document.getElementById('query');
                field.value = 'n';
                field.dispatchEvent(new Event('input', {bubbles:true}));
                """);
        try {
            wait.until(ignored -> Boolean.TRUE.equals(script.executeScript(
                    "return typeof window.__releaseResponse === 'function'")));
            script.executeScript("""
                    const field = document.getElementById('query');
                    field.value = 'north';
                    field.dispatchEvent(new Event('input', {bubbles:true}));
                    window.__releaseResponse();
                    """);
            wait.until(driver -> driver.findElement(By.id("query-result")).getText().equals("north"));
            assertEquals("north", browser.findElement(By.id("query")).getDomProperty("value"));
            assertEquals(List.of("n", "north"), script.executeScript("return window.__sentQueries"));
        } finally {
            script.executeScript("window.__releaseResponse?.(); window.fetch = window.__realFetch;");
        }
    }

    @Test
    void coalescesOptedInInputAndFlushesItBeforeAnotherAction() {
        browser.get(application.uri().resolve("forms").toString());
        var script = (JavascriptExecutor) browser;
        script.executeScript("""
                window.__realFetch = window.fetch.bind(window);
                window.__sentQueries = [];
                window.fetch = (url, options) => {
                  if (String(url).includes('/_roots/action')) {
                    window.__sentQueries.push(new URLSearchParams(options.body).get('query'));
                  }
                  return window.__realFetch(url, options);
                };
                const field = document.getElementById('query');
                field.dataset.rootsInputDebounce = '30000';
                for (const value of ['n', 'no', 'north']) {
                  field.value = value;
                  field.dispatchEvent(new Event('input', {bubbles:true}));
                }
                """);
        assertEquals(List.of(), script.executeScript("return window.__sentQueries"));
        browser.findElement(By.id("update")).click();
        wait.until(driver -> driver.findElement(By.id("updates")).getText().equals("1"));
        assertEquals("north", browser.findElement(By.id("query-result")).getText());
        assertEquals(2L, script.executeScript("return window.__sentQueries.length"));
        assertEquals("north", script.executeScript("return window.__sentQueries[0]"));
        script.executeScript("""
                const field = document.getElementById('query');
                field.dataset.rootsInputDebounce = '30';
                field.value = 'timer-fired';
                field.dispatchEvent(new Event('input', {bubbles:true}));
                """);
        wait.until(driver -> driver.findElement(By.id("query-result")).getText().equals("timer-fired"));
    }

    @Test
    void aNewViewDoesNotWaitForAnUnresolvedOldAction() {
        browser.get(application.uri().resolve("forms").toString());
        var script = (JavascriptExecutor) browser;
        script.executeScript("""
                window.__realFetch = window.fetch.bind(window);
                window.__actionRequests = 0;
                window.fetch = async (url, options) => {
                  if (!String(url).includes('/_roots/action')) return window.__realFetch(url, options);
                  const first = ++window.__actionRequests === 1;
                  const response = await window.__realFetch(url, options);
                  if (first) await new Promise(resolve => window.__releaseResponse = resolve);
                  return response;
                };
                document.getElementById('update').click();
                document.getElementById('update').click();
                """);
        try {
            wait.until(ignored -> Boolean.TRUE.equals(script.executeScript(
                    "return typeof window.__releaseResponse === 'function'")));
            var oldView = browser.findElement(By.id("roots")).getDomAttribute("data-roots-view");
            browser.findElement(By.id("fresh-form")).click();
            wait.until(driver -> !driver.findElement(By.id("roots")).getDomAttribute("data-roots-view").equals(oldView));
            browser.findElement(By.id("update")).click();
            wait.until(driver -> driver.findElement(By.id("updates")).getText().equals("1"));
            assertEquals(2L, script.executeScript("return window.__actionRequests"),
                    "A new view must run while the previous view's first response is unresolved");
            script.executeScript("window.__releaseResponse()");
            wait.until(ignored -> application.runtimeSnapshot().liveViews() == 1);
            assertEquals(2L, script.executeScript("return window.__actionRequests"),
                    "Queued old-view mutations must not be submitted after navigation");
        } finally {
            script.executeScript("window.__releaseResponse?.(); window.fetch = window.__realFetch;");
        }
    }

    @Test
    void aLostResponsePausesMutationsPreservesDraftsAndNeverRetries() {
        browser.get(application.uri().resolve("forms").toString());
        var script = (JavascriptExecutor) browser;
        int before = com.chaplin.roots.browser.pages.forms.Page.updateActions();
        script.executeScript("""
                window.__realFetch = window.fetch.bind(window);
                window.__actionRequests = 0;
                window.__uncertain = [];
                window.__blocked = 0;
                window.addEventListener('roots:action-uncertain', e => window.__uncertain.push(e.detail.reason));
                window.addEventListener('roots:action-blocked', () => window.__blocked++);
                window.fetch = async (url, options) => {
                  if (!String(url).includes('/_roots/action')) return window.__realFetch(url, options);
                  window.__actionRequests++;
                  const response = await window.__realFetch(url, options);
                  // Deliver headers but hold the body, even if AbortSignal is ignored.
                  return {status: response.status, ok: response.ok, json: async () => {
                    const body = await response.json();
                    await new Promise(resolve => window.__releaseResponse = resolve);
                    return body;
                  }};
                };
                const draft = document.getElementById('draft');
                draft.value = 'Keep this draft';
                draft.dispatchEvent(new Event('input', {bubbles:true}));
                document.getElementById('update').dataset.rootsActionTimeout = '1000';
                document.getElementById('update').click();
                document.getElementById('update').click();
                """);
        try {
            wait.until(ignored -> Boolean.TRUE.equals(script.executeScript(
                    "return document.getElementById('roots').dataset.rootsActionState === 'uncertain'")));
            assertEquals(before + 1, com.chaplin.roots.browser.pages.forms.Page.updateActions());
            assertEquals(List.of("timeout"), script.executeScript("return window.__uncertain"));
            assertEquals(1L, script.executeScript("return window.__actionRequests"));
            assertEquals("Keep this draft", browser.findElement(By.id("draft")).getDomProperty("value"));
            assertTrue(browser.findElement(By.id("roots-action-recovery")).getText().contains("check whether"));
            browser.findElement(By.id("update")).click();
            wait.until(ignored -> ((Number) script.executeScript("return window.__blocked")).intValue() >= 2);
            assertEquals(1L, script.executeScript("return window.__actionRequests"));
            script.executeScript("window.__releaseResponse?.(); window.fetch = window.__realFetch;");
            browser.findElement(By.id("fresh-form")).click();
            wait.until(driver -> driver.findElements(By.id("roots-action-recovery")).isEmpty());
            browser.findElement(By.id("update")).click();
            wait.until(driver -> driver.findElement(By.id("updates")).getText().equals("1"));
            assertEquals(before + 2, com.chaplin.roots.browser.pages.forms.Page.updateActions());
        } finally {
            script.executeScript("window.__releaseResponse?.(); window.fetch = window.__realFetch;");
        }
    }

    @Test
    void serverAndProxyFailuresPauseActionsEvenWhenAnErrorResponseArrives() {
        var script = (JavascriptExecutor) browser;
        for (var mode : List.of("render", "handler", "mapped", "proxy")) {
            browser.get(application.uri().resolve("outcomes?mode=" + mode).toString());
            int before = com.chaplin.roots.browser.pages.outcomes.Page.WRITES.get();
            script.executeScript("""
                    window.__realFetch = window.fetch.bind(window);
                    window.__actionRequests = 0;
                    window.__uncertain = [];
                    window.__blocked = 0;
                    window.addEventListener('roots:action-uncertain', e => window.__uncertain.push(e.detail.reason));
                    window.addEventListener('roots:action-blocked', () => window.__blocked++);
                    const proxyFailure = arguments[0];
                    window.fetch = async (url, options) => {
                      if (!String(url).includes('/_roots/action')) return window.__realFetch(url, options);
                      window.__actionRequests++;
                      const response = await window.__realFetch(url, options);
                      if (!proxyFailure) return response;
                      await response.arrayBuffer();
                      return new Response('{"error":"Upstream response lost"}', {
                        status: 502, headers: {'Content-Type':'application/json'}
                      });
                    };
                    """, mode.equals("proxy"));
            try {
                browser.findElement(By.id("outcome-draft")).sendKeys("Keep the unsaved draft");
                script.executeScript("document.getElementById('outcome-write').click(); document.getElementById('outcome-write').click()");
                wait.until(ignored -> Boolean.TRUE.equals(script.executeScript(
                        "return document.getElementById('roots').dataset.rootsActionState === 'uncertain'")));
                assertEquals(before + 1, com.chaplin.roots.browser.pages.outcomes.Page.WRITES.get(), mode);
                assertEquals(List.of("server"), script.executeScript("return window.__uncertain"), mode);
                assertEquals(1L, script.executeScript("return window.__actionRequests"), mode);
                assertEquals("Keep the unsaved draft", browser.findElement(By.id("outcome-draft")).getDomProperty("value"));
                assertTrue(browser.findElement(By.id("roots-action-recovery")).isDisplayed());
                browser.findElement(By.id("outcome-write")).click();
                wait.until(ignored -> ((Number) script.executeScript("return window.__blocked")).intValue() >= 2);
                assertEquals(1L, script.executeScript("return window.__actionRequests"));
                script.executeScript("window.fetch = window.__realFetch");
                browser.findElement(By.id("outcome-fresh")).click();
                wait.until(driver -> driver.findElements(By.id("roots-action-recovery")).isEmpty());
                browser.findElement(By.id("outcome-write")).click();
                wait.until(driver -> driver.findElement(By.id("outcome-writes")).getText().equals("1"));
                assertEquals(before + 2, com.chaplin.roots.browser.pages.outcomes.Page.WRITES.get());
            } finally {
                script.executeScript("window.fetch = window.__realFetch");
            }
        }
    }

    @Test
    void expiredOrIncompatibleViewsPreserveUnacknowledgedEdits() {
        var script = (JavascriptExecutor) browser;
        for (var mode : List.of("expiry", "protocol")) {
            browser.get(application.uri().resolve("outcomes").toString());
            var view = browser.findElement(By.id("roots")).getDomAttribute("data-roots-view");
            int before = com.chaplin.roots.browser.pages.outcomes.Page.WRITES.get();
            script.executeScript("""
                    window.__realFetch = window.fetch.bind(window);
                    window.__recovery = [];
                    window.addEventListener('roots:view-recovery', e => window.__recovery.push(e.detail.reason));
                    const mode = arguments[0];
                    window.fetch = (url, options) => {
                      if (!String(url).includes('/_roots/action')) return window.__realFetch(url, options);
                      return Promise.resolve(new Response(mode === 'expiry' ? '' : JSON.stringify({protocol: 'incompatible'}),
                        {status: mode === 'expiry' ? 409 : 200, headers: {'Content-Type': 'application/json'}}));
                    };
                    """, mode);
            browser.findElement(By.id("outcome-draft")).sendKeys("Retain these unacknowledged edits");
            browser.findElement(By.id("outcome-write")).click();
            wait.until(driver -> "expired".equals(driver.findElement(By.id("roots")).getDomAttribute("data-roots-action-state")));
            assertEquals(view, browser.findElement(By.id("roots")).getDomAttribute("data-roots-view"));
            assertEquals("Retain these unacknowledged edits", browser.findElement(By.id("outcome-draft")).getDomProperty("value"));
            assertEquals(List.of(mode), script.executeScript("return window.__recovery"));
            assertEquals(before, com.chaplin.roots.browser.pages.outcomes.Page.WRITES.get());
            script.executeScript("window.fetch = window.__realFetch");
            browser.findElement(By.id("roots-action-recovery")).findElement(By.tagName("button")).click();
            wait.until(ignored -> Boolean.TRUE.equals(script.executeScript(
                    "const view = document.getElementById('roots')?.dataset.rootsView; return !!view && view !== arguments[0]", view)));
            browser.findElement(By.id("outcome-write")).click();
            wait.until(driver -> driver.findElement(By.id("outcome-writes")).getText().equals("1"));
            assertEquals(before + 1, com.chaplin.roots.browser.pages.outcomes.Page.WRITES.get());
        }
    }

    @Test
    void streamReloadPreservesDirtyControlsButRefreshesAnUneditedView() {
        var script = (JavascriptExecutor) browser;
        for (boolean dirty : List.of(false, true)) {
            browser.get(application.uri().resolve("outcomes?mode=handler").toString());
            script.executeScript("""
                    const NativeEventSource = window.EventSource;
                    window.EventSource = class extends NativeEventSource {
                      constructor(...args) { super(...args); window.__stream = this; }
                    };
                    """);
            browser.findElement(By.id("outcome-fresh")).click();
            wait.until(ignored -> Boolean.TRUE.equals(script.executeScript("return !!window.__stream")));
            var view = browser.findElement(By.id("roots")).getDomAttribute("data-roots-view");
            if (dirty) browser.findElement(By.id("outcome-draft")).sendKeys("Keep on expiry");
            script.executeScript("window.__stream.dispatchEvent(new MessageEvent('reload', {data: '{}'}))");
            if (dirty) {
                wait.until(driver -> "expired".equals(driver.findElement(By.id("roots")).getDomAttribute("data-roots-action-state")));
                assertEquals(view, browser.findElement(By.id("roots")).getDomAttribute("data-roots-view"));
                assertEquals("Keep on expiry", browser.findElement(By.id("outcome-draft")).getDomProperty("value"));
            } else {
                wait.until(ignored -> Boolean.TRUE.equals(script.executeScript(
                        "const view = document.getElementById('roots')?.dataset.rootsView; return !!view && view !== arguments[0]", view)));
            }
        }
    }

    @Test
    void boundsQueuedActionsAndReportsBackpressure() {
        browser.get(application.uri().resolve("forms").toString());
        var script = (JavascriptExecutor) browser;
        script.executeScript("""
                window.__backpressure = 0;
                window.__queueErrors = 0;
                window.addEventListener('roots:backpressure', () => window.__backpressure++);
                window.addEventListener('roots:error', event => {
                  if (event.detail.name === 'RootsQueueFullError') window.__queueErrors++;
                });
                const field = document.getElementById('query');
                for (let index = 0; index < 150; index++) {
                  field.value = String(index);
                  field.dispatchEvent(new Event('input', {bubbles:true}));
                }
                """);
        assertEquals(22L, script.executeScript("return window.__backpressure"));
        assertEquals(22L, script.executeScript("return window.__queueErrors"));
        wait.until(driver -> driver.findElement(By.id("query-result")).getText().equals("127"));
        // Rejected events remain visible as unsent edits, rather than being lost.
        assertEquals("149", browser.findElement(By.id("query")).getDomProperty("value"));
        script.executeScript("document.getElementById('query').dispatchEvent(new Event('input', {bubbles:true}))");
        wait.until(driver -> driver.findElement(By.id("query-result")).getText().equals("149"));
    }

    @Test
    void shippedExamplesPassWcag22AaAndBestPracticeAudits() {
        try (var chat = Roots.start(RootsConfig.forApplication(com.chaplin.roots.examples.chat.Application.class)
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

            browser.get(enterprise.uri().resolve("latency").toString());
            wait.until(driver -> driver.findElements(By.id("latency-range")).size() == 1);
            ((JavascriptExecutor) browser).executeScript("const r=document.getElementById('latency-range'); r.value='8'; r.dispatchEvent(new Event('input',{bubbles:true})); window.chartRange=r");
            browser.findElement(By.id("refresh-samples")).click();
            wait.until(driver -> driver.findElement(By.id("sample-set")).getText().equals("1"));
            assertEquals(Boolean.TRUE, ((JavascriptExecutor) browser).executeScript("return chartRange===document.getElementById('latency-range') && chartRange.value==='8'"));
            assertAccessible("enterprise latency widget example");
            if (browserName().equals("Chrome") && System.getProperty("roots.browser.screenshot") != null) {
                try {
                    var screenshot = Path.of(System.getProperty("roots.browser.screenshot"));
                    Files.createDirectories(screenshot.toAbsolutePath().getParent());
                    Files.write(screenshot, ((org.openqa.selenium.TakesScreenshot) browser).getScreenshotAs(org.openqa.selenium.OutputType.BYTES));
                    var size = browser.manage().window().getSize();
                    try {
                        browser.manage().window().setSize(new org.openqa.selenium.Dimension(390, 844));
                        assertEquals(Boolean.TRUE, ((JavascriptExecutor) browser).executeScript("return document.documentElement.scrollWidth <= window.innerWidth"));
                        Files.write(screenshot.resolveSibling(screenshot.getFileName() + ".mobile.png"),
                                ((org.openqa.selenium.TakesScreenshot) browser).getScreenshotAs(org.openqa.selenium.OutputType.BYTES));
                    } finally { browser.manage().window().setSize(size); }
                } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }
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

        com.chaplin.roots.browser.pages.Page.holdOptimisticActions();
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
            com.chaplin.roots.browser.pages.Page.releaseOptimisticActions();
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
        com.chaplin.roots.browser.pages.Page.holdOptimisticActions();
        try {
            portalHost.findElement(By.id("portal-close")).click();
            wait.until(ignored -> Boolean.TRUE.equals(script.executeScript("""
                    const dialog = arguments[0];
                    const scope = document.getElementById('portal-pending-scope');
                    return dialog.hidden && scope.dataset.rootsPending === 'true'
                      && scope.getAttribute('aria-busy') === 'true';
                    """, portalDialog)));
        } finally {
            com.chaplin.roots.browser.pages.Page.releaseOptimisticActions();
        }
        wait.until(driver -> driver.findElements(By.cssSelector("[data-roots-portal-host]")).isEmpty());
        assertEquals("12", browser.findElement(By.id("roots")).getAttribute("data-roots-revision"));
        assertEquals(List.of(), script.executeScript("return window.__rootsErrors"));

        com.chaplin.roots.browser.pages.Page.holdOptimisticActions();
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
            com.chaplin.roots.browser.pages.Page.releaseOptimisticActions();
        }
        wait.until(driver -> driver.findElement(By.id("optimistic-failure")).getText().equals("Fail safely"));
        assertTrue(browser.findElement(By.id("optimistic-failure")).isEnabled());
        assertEquals("original", browser.findElement(By.id("optimistic-value")).getAttribute("value"));
        wait.until(ignored -> ((List<?>) script.executeScript("return window.__rootsErrors")).size() == 1);
        assertNull(browser.findElement(By.id("failure-scope")).getAttribute("data-roots-pending"));
        assertEquals("false", browser.findElement(By.id("failure-scope")).getAttribute("aria-busy"));
        assertNull(script.executeScript("return document.documentElement.dataset.rootsPending"));
        script.executeScript("window.__rootsErrors = []");

        assertEquals("uncertain", browser.findElement(By.id("roots")).getDomAttribute("data-roots-action-state"));

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
        // Metadata nodes are deliberately replaced during the commit. Read the
        // selector and attribute in one browser task instead of retaining a
        // WebElement between two transport calls across that replacement.
        wait.until(ignored -> "#553399".equals(script.executeScript(
                "return document.querySelector('meta[name=\"theme-color\"]')?.getAttribute('content')")));
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
            com.chaplin.roots.browser.pages.Page.pushBackgroundUpdate();
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
