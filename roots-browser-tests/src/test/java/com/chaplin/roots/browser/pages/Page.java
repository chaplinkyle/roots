package com.chaplin.roots.browser.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.ActionEvent;
import com.chaplin.roots.OptimisticEffect;
import com.chaplin.roots.Ref;
import com.chaplin.roots.ResponseCookie;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;
import com.chaplin.roots.validation.ConstraintValidator;
import com.chaplin.roots.validation.Email;
import com.chaplin.roots.validation.FormConstraint;
import com.chaplin.roots.validation.FormField;
import com.chaplin.roots.validation.FormModel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.chaplin.roots.html.Html.button;
import static com.chaplin.roots.html.Html.div;
import static com.chaplin.roots.html.Html.h1;
import static com.chaplin.roots.html.Html.form;
import static com.chaplin.roots.html.Html.input;
import static com.chaplin.roots.html.Html.label;
import static com.chaplin.roots.html.Html.link;
import static com.chaplin.roots.html.Html.p;
import static com.chaplin.roots.html.Html.modal;
import static com.chaplin.roots.html.Html.span;
import static com.chaplin.roots.html.Html.validationMessage;
import static com.chaplin.roots.html.Html.validationSummary;

@PageMetadata(title = "Roots browser fixture", stylesheets = "/base.css")
public final class Page implements com.chaplin.roots.Page {
    private static final AtomicReference<Page> MOUNTED = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> OPTIMISTIC_ACTION_GATE =
            new AtomicReference<>(new CountDownLatch(0));
    private static final AtomicReference<CountDownLatch> OPTIMISTIC_ACTION_COMPLETED =
            new AtomicReference<>(new CountDownLatch(0));
    private static final AtomicReference<CountDownLatch> VALIDATION_ACTION_GATE =
            new AtomicReference<>(new CountDownLatch(0));
    private static final AtomicReference<CountDownLatch> VALIDATION_ACTION_ENTERED =
            new AtomicReference<>(new CountDownLatch(0));
    private static final AtomicReference<CountDownLatch> VALIDATION_ACTION_COMPLETED =
            new AtomicReference<>(new CountDownLatch(0));
    private static final AtomicInteger VALIDATION_ACTION_INVOCATIONS = new AtomicInteger();

    private final InspectionProbe inspectionProbe = new InspectionProbe();
    private final ScopedCounter scopedCounter = new ScopedCounter();
    private int count;
    private String uploadSummary = "No upload yet";
    private String eventInput = "";
    private String eventSummary = "No extended event yet";
    private String validatedEmail = "";
    private String validationStatus = "Not validated";
    private final Ref counter = Ref.create();
    private final Ref failingAction = Ref.create();
    private final Ref failingValue = Ref.create();
    private final Ref effectInput = Ref.create();
    private final Ref portalInput = Ref.create();
    private final Ref portalDialog = Ref.create();
    private boolean overlayOpen;
    private boolean overlayBackdropDismiss = true;
    private String portalName = "nobody";
    private int backgroundCount;
    private int transitionCount;
    private String cookieStatus = "No cookie action yet";
    private PageContext context;

    @Override
    public Node render(PageContext context) {
        return div(
                h1("Browser fixture"),
                div(
                        div(
                                button("Count ", count).id("counter").ref(counter)
                                        .optimistic(
                                                OptimisticEffect.text(counter, "Count pending"),
                                                OptimisticEffect.disable(counter)
                                        )
                                        .onClick(this, "increment")
                        ).id("counter-scope").pendingScope(),
                        div(
                                button("Fail safely").id("optimistic-failure").ref(failingAction)
                                        .optimistic(
                                                OptimisticEffect.text(failingAction, "Working"),
                                                OptimisticEffect.value(failingValue, "temporary"),
                                                OptimisticEffect.disable(failingAction)
                                        )
                                        .onClick(this, "failOptimistically"),
                                label("Optimistic value",
                                        input().id("optimistic-value").ref(failingValue).value("original"))
                        ).id("failure-scope").aria("busy", "false").pendingScope()
                ).id("outer-pending-scope").pendingScope(),
                form(
                        label("Work email",
                                input().id("validation-email").name("email").value(validatedEmail)
                                        .aria("invalid", "false").aria("describedby", "email-help")),
                        span("Use a work email").id("email-help"),
                        validationMessage("email"),
                        validationSummary().id("validation-summary"),
                        button("Validate email").type("submit").id("validation-submit")
                ).id("validation-form").onSubmit(this, "validateEmail"),
                div(validationStatus).id("validation-status"),
                label("Input event",
                        input().id("event-input").name("eventInput").value(eventInput)
                                .onInput(this, "captureInput")),
                label("Keyboard event",
                        input().id("event-key")
                                .onFocus(this, "captureFocus")
                                .onKeyDown(this, "captureKey")),
                button("Pointer event").type("button").id("event-pointer")
                        .onPointerDown(this, "capturePointer"),
                div(eventSummary).id("event-result"),
                div("Background ", backgroundCount).id("background-result"),
                button("Transition ", transitionCount).id("transition-action")
                        .onClick(this, "transitionUpdate"),
                button("No DOM transition").id("transition-no-dom")
                        .onClick(this, "transitionWithoutDomChange"),
                label("Typed effect target",
                        input().id("effect-input").name("effectInput").value("select this text")
                                .ref(effectInput)
                                .onKeyDown(this, "blurTypedEffect")
                                .onBlur(this, "captureFocus")),
                button("Run typed effects").id("effect-run").onClick(this, "runTypedEffects"),
                button("Redirect to fast fragment").id("action-fragment-redirect")
                        .onClick(this, "redirectToFastFragment"),
                div("seed=", context.cookie("browser-seed").orElse("missing"), "; ", cookieStatus)
                        .id("cookie-status"),
                button("Set browser cookie").id("cookie-set").onClick(this, "setBrowserCookie"),
                button("Delete browser cookie").id("cookie-delete").onClick(this, "deleteBrowserCookie"),
                form(
                        label("Attachment", input().type("file").name("attachment").id("upload-file")),
                        button("Upload file").type("submit").id("upload-submit")
                ).onSubmit(this, "upload"),
                div(uploadSummary).id("upload-result"),
                button("Open Java overlay").id("open-overlay").onClick(this, "openOverlay"),
                button("Open locked Java overlay").id("open-locked-overlay").onClick(this, "openLockedOverlay"),
                div(scopedCounter, span("Outside scoped boundary").id("scoped-sentinel")).id("scoped-shell"),
                overlayOpen ? modal(
                        "browser-overlay", "Portal fixture", this, "closeOverlay",
                        p("Hello, ", portalName).id("portal-greeting"),
                        form(
                                label("Name",
                                        input().name("portalName").id("portal-name").ref(portalInput)
                                                .onFocus(this, "captureFocus")),
                                button("Save").type("submit").id("portal-save")
                        ).onSubmit(this, "saveOverlay"),
                        button("Close").type("button").id("portal-close")
                                .optimistic(OptimisticEffect.hide(portalDialog))
                                .onClick(this, "closeOverlay")
                ).initialFocus(portalInput)
                        .ref(portalDialog)
                        .dismissOnBackdrop(overlayBackdropDismiss)
                        .id("portal-pending-scope")
                        .className("portal-dialog") : null,
                link("/themed", "Themed page").id("themed-link").viewTransition(),
                link("/slow", "Slow page").id("slow-link"),
                link("/fast", "Fast page").id("fast-link"),
                link("#home-fragment-target", "Home fragment").id("home-fragment-link"),
                link("/fast#fast-fragment-target", "Fast fragment").id("fast-fragment-link"),
                link("/missing-customer", "Missing page").id("missing-link"),
                link("/broken", "Broken page").id("broken-link"),
                div().className("fragment-spacer").aria("hidden", "true"),
                p("Home fragment target").id("home-fragment-target"),
                div().className("fragment-spacer").aria("hidden", "true"),
                inspectionProbe,
                inspectionProbe
        );
    }

    @Override
    public void onMount(PageContext context) {
        this.context = context;
        MOUNTED.set(this);
    }

    @Override
    public void onUnmount(PageContext context) {
        MOUNTED.compareAndSet(this, null);
        this.context = null;
    }

    public static void pushBackgroundUpdate() {
        var page = MOUNTED.get();
        if (page == null || page.context == null) {
            throw new IllegalStateException("No browser fixture is mounted");
        }
        page.context.update(() -> page.backgroundCount++);
    }

    public static void holdOptimisticActions() {
        var previous = OPTIMISTIC_ACTION_GATE.getAndSet(new CountDownLatch(1));
        previous.countDown();
        OPTIMISTIC_ACTION_COMPLETED.getAndSet(new CountDownLatch(1)).countDown();
    }

    public static void releaseOptimisticActions() {
        OPTIMISTIC_ACTION_GATE.getAndSet(new CountDownLatch(0)).countDown();
    }

    public static boolean awaitOptimisticActionCompletion() throws InterruptedException {
        return OPTIMISTIC_ACTION_COMPLETED.get().await(5, TimeUnit.SECONDS);
    }

    public static void holdValidationActions() {
        VALIDATION_ACTION_GATE.getAndSet(new CountDownLatch(1)).countDown();
        VALIDATION_ACTION_ENTERED.set(new CountDownLatch(1));
        VALIDATION_ACTION_COMPLETED.getAndSet(new CountDownLatch(1)).countDown();
    }

    public static void releaseValidationActions() {
        VALIDATION_ACTION_GATE.getAndSet(new CountDownLatch(0)).countDown();
    }

    public static boolean awaitValidationActionStart() throws InterruptedException {
        return VALIDATION_ACTION_ENTERED.get().await(5, TimeUnit.SECONDS);
    }

    public static boolean awaitValidationActionCompletion() throws InterruptedException {
        return VALIDATION_ACTION_COMPLETED.get().await(5, TimeUnit.SECONDS);
    }

    public static int validationActionInvocations() {
        return VALIDATION_ACTION_INVOCATIONS.get();
    }

    @ServerAction
    private void increment(ActionEvent event) {
        try {
            pauseForOptimisticObservation();
            count++;
            eventSummary = event.type() + ":button=" + event.browser().button().orElse(-1);
        } finally {
            OPTIMISTIC_ACTION_COMPLETED.get().countDown();
        }
    }

    @ServerAction
    private void failOptimistically() {
        pauseForOptimisticObservation();
        throw new IllegalStateException("expected optimistic failure");
    }

    @ServerAction
    private void validateEmail(ActionEvent event) {
        VALIDATION_ACTION_INVOCATIONS.incrementAndGet();
        VALIDATION_ACTION_ENTERED.get().countDown();
        try {
            pauseForValidationObservation();
            var form = event.bind(EmailForm.class);
            validatedEmail = form.email();
            validationStatus = "Accepted " + form.email();
        } finally {
            VALIDATION_ACTION_COMPLETED.get().countDown();
        }
    }

    @ServerAction
    private void captureInput(ActionEvent event) {
        eventInput = event.required("eventInput");
        eventSummary = event.type() + ":" + eventInput;
    }

    @ServerAction
    private void captureKey(ActionEvent event) {
        eventSummary = event.type()
                + ":" + event.browser().key().orElse("missing")
                + ":" + event.browser().code().orElse("missing")
                + ":control=" + event.browser().controlKey();
    }

    @ServerAction
    private void captureFocus(ActionEvent event) {
        eventSummary = event.type() + ":user";
    }

    @ServerAction
    private void capturePointer(ActionEvent event) {
        eventSummary = event.type()
                + ":button=" + event.browser().button().orElse(-1)
                + ":x=" + event.browser().clientX().orElse(-1)
                + ":y=" + event.browser().clientY().orElse(-1);
    }

    @ServerAction
    private void upload(ActionEvent event) {
        var file = event.file("attachment").orElseThrow();
        uploadSummary = file.filename() + ":" + file.contentText().trim();
    }

    @ServerAction
    private void transitionUpdate(ActionEvent event) {
        transitionCount++;
        event.viewTransition();
    }

    @ServerAction
    private void transitionWithoutDomChange(ActionEvent event) {
        event.viewTransition();
    }

    @ServerAction
    private void runTypedEffects(ActionEvent event) {
        event.selectText(effectInput);
        event.announce("Settings saved");
        event.announceAssertively("Critical update available");
    }

    @ServerAction
    private void redirectToFastFragment(ActionEvent event) {
        event.viewTransition();
        event.redirect("/fast#fast-fragment-target");
    }

    @ServerAction
    private void blurTypedEffect(ActionEvent event) {
        event.blur(effectInput);
        event.announce("Focus cleared");
    }

    @ServerAction
    private void setBrowserCookie(ActionEvent event) {
        cookieStatus = "set from seed=" + event.cookie("browser-seed").orElse("missing");
        event.setCookie(ResponseCookie.builder("browser-preference", "dense")
                .path(event.cookiePath())
                .httpOnly(false)
                .build());
    }

    @ServerAction
    private void deleteBrowserCookie(ActionEvent event) {
        cookieStatus = "deleted value=" + event.cookie("browser-preference").orElse("missing");
        event.deleteCookie("browser-preference");
    }

    @ServerAction
    private void openOverlay(ActionEvent event) {
        overlayOpen = true;
        overlayBackdropDismiss = true;
        event.focus(portalInput);
    }

    @ServerAction
    private void openLockedOverlay(ActionEvent event) {
        overlayOpen = true;
        overlayBackdropDismiss = false;
        event.focus(portalInput);
    }

    @ServerAction
    private void saveOverlay(ActionEvent event) {
        portalName = event.required("portalName").strip();
        event.focus(portalInput);
    }

    @ServerAction
    private void closeOverlay() {
        pauseForOptimisticObservation();
        overlayOpen = false;
    }

    private static void pauseForOptimisticObservation() {
        try {
            if (!OPTIMISTIC_ACTION_GATE.get().await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Browser test did not release the optimistic action gate");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("browser fixture action interrupted", interrupted);
        }
    }

    private static void pauseForValidationObservation() {
        try {
            if (!VALIDATION_ACTION_GATE.get().await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Browser test did not release the validation action gate");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("browser fixture validation interrupted", interrupted);
        }
    }

    @FormModel(message = "Check the highlighted fields")
    private record EmailForm(
            @FormField(trim = true)
            @Email(message = "Enter a valid email.")
            @EndsWith(value = "@example.com", message = "Markup stays text: <script>unsafe</script>") String email
    ) {
    }

    @Target(ElementType.RECORD_COMPONENT)
    @Retention(RetentionPolicy.RUNTIME)
    @FormConstraint(validatedBy = EndsWithValidator.class)
    private @interface EndsWith {
        String value();

        String message();
    }

    /** Browser-fixture custom form constraint. */
    public static final class EndsWithValidator implements ConstraintValidator<EndsWith> {
        /** Creates the stateless validator. */
        public EndsWithValidator() {
        }

        @Override
        public Optional<String> validate(Object value, EndsWith constraint) {
            return value instanceof String text && text.endsWith(constraint.value())
                    ? Optional.empty()
                    : Optional.of(constraint.message());
        }
    }
}
