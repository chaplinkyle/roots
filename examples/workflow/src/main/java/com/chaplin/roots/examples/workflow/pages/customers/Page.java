package com.chaplin.roots.examples.workflow.pages.customers;

import com.chaplin.roots.ActionEvent;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.Authorize;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.examples.workflow.CustomerRepository;
import com.chaplin.roots.html.Node;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import static com.chaplin.roots.html.Html.*;

@PageMetadata(title = "Customer directory", stylesheets = "/workflow.css", robots = "noindex, nofollow")
public final class Page implements com.chaplin.roots.Page {
    private final CustomerRepository repository;
    public Page(CustomerRepository repository) { this.repository = repository; }
    @Override public Node render(PageContext context) {
        var actor = context.identity().orElseThrow();
        var search = context.query("q").orElse("");
        var page = repository.customers(actor, search, context.query("before").orElse(""));
        return section(
                div(div(h1("Customer directory"), p("Current contact details. Each save records who made the change.")),
                        actor.hasRole("EDITOR") ? button("New customer").onClick(this, "create").className("button") : null
                ).className("heading"),
                form(label("Company starts with", input().name("q").value(search).attr("maxlength", "120")),
                        button("Search").type("submit")).attr("method", "get").attr("action", "/customers").className("search"),
                page.items().isEmpty() ? p("No matching customers. Start a new customer draft or change the search.").className("empty")
                        : div(table(tag("caption", "Customer contact details"),
                                thead(tr(th("Company").attr("scope", "col"), th("Contact").attr("scope", "col"),
                                        th("Email").attr("scope", "col"), th("Version").attr("scope", "col"))),
                                tbody(page.items().stream().map(c -> tr(
                                        td(a(c.fields().company()).href("/customers/" + c.id())),
                                        td(c.fields().contact()), td(c.fields().email()), td(c.version()))).toList()))).className("table-scroll"),
                nav(span(page.items().size(), " customers shown"),
                        context.query("before").isPresent() ? a("First page").href("/customers?q=" + encode(search)) : null,
                        page.next() == null ? null : a("Next 25").href("/customers?q=" + encode(search) + "&before=" + encode(page.next()))
                ).className("pagination").attr("aria-label", "Directory pages")
        );
    }
    @ServerAction("create") @Authorize("edit")
    private void create(ActionEvent event) {
        var draft = repository.create(event.identity().orElseThrow(), null);
        event.redirect("/drafts/" + draft.id());
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
