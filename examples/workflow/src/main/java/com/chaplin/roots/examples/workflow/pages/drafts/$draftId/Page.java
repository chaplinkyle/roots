package com.chaplin.roots.examples.workflow.pages.drafts.$draftId;

import com.chaplin.roots.ActionEvent;
import com.chaplin.roots.AuthenticatedIdentity;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.ValidationException;
import com.chaplin.roots.annotation.Authorize;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.examples.workflow.CustomerRepository;
import com.chaplin.roots.examples.workflow.CustomerRepository.Draft;
import com.chaplin.roots.examples.workflow.CustomerRepository.Fields;
import com.chaplin.roots.html.Node;
import java.time.Instant;
import static com.chaplin.roots.html.Html.*;

@Authorize("edit")
@PageMetadata(title = "Customer draft", stylesheets = "/workflow.css", robots = "noindex, nofollow")
public final class Page implements com.chaplin.roots.Page {
    private final CustomerRepository repository;
    private Draft draft;
    private String notice;
    public Page(CustomerRepository repository) { this.repository = repository; }

    @Override public Node render(PageContext context) {
        var actor = context.identity().orElseThrow();
        if (draft == null) draft = repository.draft(actor, CustomerRepository.id(context.parameter("draftId")));
        if (draft.completed()) return section(h1("Customer saved"),
                p("This draft was already saved to the customer record. No further changes were applied."),
                a("Open saved customer").href("/customers/" + draft.customerId()).className("button"));
        var current = draft.customerId() == null ? null : repository.customer(actor, draft.customerId());
        boolean changed = current != null && current.version() != draft.baseVersion();
        long displayedCustomerVersion = current == null ? 0 : current.version();
        return section(
                a("My drafts").href("/drafts"),
                h1(draft.customerId() == null ? "New customer" : "Edit customer"),
                p("Changes are saved privately as you type. Save customer when the details are ready."),
                notice == null ? null : p(notice).className("notice").attr("role", "status"),
                div(form(
                        input().type("hidden").name("reviewVersion").value(Long.toString(displayedCustomerVersion)),
                        label("Company", input().name("company").value(draft.fields().company()).attr("maxlength", "120")),
                        validationMessage("company"),
                        label("Contact name", input().name("contact").value(draft.fields().contact()).attr("maxlength", "120")),
                        validationMessage("contact"),
                        label("Email", input().name("email").value(draft.fields().email()).type("email").attr("maxlength", "254")),
                        validationMessage("email"), validationSummary(),
                        p("Last confirmed draft save: ", Instant.ofEpochMilli(draft.updatedAt()).toString())
                                .className("saved").attr("data-draft-version", Long.toString(draft.version())),
                        p("Keep this draft URL to return after a restart. Wait for a confirmed save before leaving.").className("hint"),
                        div(button("Save customer").type("submit").className("button").attr("disabled", changed),
                                changed ? button("I reviewed the saved values; keep my draft")
                                        .type("button").onClick(this, "review").className("secondary") : null).className("actions")
                ).onSubmit(this, "complete").onInput(this, "save").debounceInput(java.time.Duration.ofMillis(400))
                        .attr("novalidate", true).className("editor").pendingScope(),
                        current == null ? aside(h2("Private until saved"),
                                p("This draft has not created a customer. Only your signed-in account can recover it.")).className("comparison")
                                : aside(h2(changed ? "This customer changed" : "Current saved values"),
                                        p(changed ? "Compare each field before keeping your draft. Saving will replace these values."
                                                : "Your draft is based on this saved version."),
                                        tag("dl", tag("dt", "Company"), tag("dd", current.fields().company()), tag("dt", "Contact"), tag("dd", current.fields().contact()),
                                                tag("dt", "Email"), tag("dd", current.fields().email())),
                                        p("Saved version ", current.version())).className(changed ? "comparison conflict" : "comparison")
                ).className("draft-grid")
        );
    }

    @ServerAction("save")
    private void save(ActionEvent event) {
        saveFields(event);
        notice = null;
    }

    private void saveFields(ActionEvent event) {
        try {
            draft = repository.save(actor(event), draft.id(), draft.version(),
                    new Fields(field(event, "company"), field(event, "contact"), field(event, "email")));
        } catch (CustomerRepository.Conflict conflict) {
            // A normal patch acknowledges captured controls. Reject without acknowledgment
            // when another tab won the draft write, preserving the user's unsaved typing.
            throw ValidationException.field("company", conflict.getMessage()
                    + " Your current typing has not replaced the saved draft. Keep a copy before reopening this draft URL.");
        }
    }

    private static String field(ActionEvent event, String name) {
        if (event.values(name).size() != 1) throw new IllegalArgumentException("Submit each draft field exactly once");
        return event.value(name).orElseThrow();
    }

    @ServerAction("complete")
    private void complete(ActionEvent event) {
        try {
            var stored = repository.draft(actor(event), draft.id());
            if (stored.completed()) throw ValidationException.field("company",
                    "This draft is already saved. Your current typing was not applied. Keep a copy before reopening this draft URL.");
            saveFields(event);
            var customerId = repository.complete(actor(event), draft.id(), draft.version());
            draft = repository.draft(actor(event), draft.id());
            event.redirect("/customers/" + customerId);
        } catch (CustomerRepository.Conflict conflict) { notice = conflict.getMessage(); }
    }

    @ServerAction("review")
    private void review(ActionEvent event) {
        // Capture the version actually displayed when this click was enqueued. An earlier
        // queued autosave can rerender the server page before this action runs.
        long reviewedVersion = Long.parseLong(field(event, "reviewVersion"));
        try {
            saveFields(event);
            draft = repository.review(actor(event), draft.id(), draft.version(), reviewedVersion);
            notice = "Review recorded. Check your draft, then select Save customer.";
        } catch (CustomerRepository.Conflict conflict) { notice = conflict.getMessage(); }
    }

    private static AuthenticatedIdentity actor(ActionEvent event) { return event.identity().orElseThrow(); }
}
