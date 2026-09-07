package com.chaplin.roots.examples.workflow.pages.drafts;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.Authorize;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.examples.workflow.CustomerRepository;
import com.chaplin.roots.html.Node;
import java.time.Instant;
import static com.chaplin.roots.html.Html.*;

@Authorize("edit")
@PageMetadata(title = "My customer drafts", stylesheets = "/workflow.css", robots = "noindex, nofollow")
public final class Page implements com.chaplin.roots.Page {
    private final CustomerRepository repository;
    public Page(CustomerRepository repository) { this.repository = repository; }
    @Override public Node render(PageContext context) {
        var drafts = repository.drafts(context.identity().orElseThrow());
        return section(h1("My drafts"), p("Your 25 most recently saved, unfinished drafts. Only you can open them."),
                drafts.isEmpty() ? p("No unfinished drafts. Open the directory to create or edit a customer.").className("empty")
                        : ul(drafts.stream().map(draft -> li(
                                a(draft.fields().company().isBlank() ? "Untitled customer" : draft.fields().company())
                                        .href("/drafts/" + draft.id()),
                                span("Saved ", Instant.ofEpochMilli(draft.updatedAt()).toString())).className("draft-row")).toList()),
                a("Open directory").href("/customers"));
    }
}
