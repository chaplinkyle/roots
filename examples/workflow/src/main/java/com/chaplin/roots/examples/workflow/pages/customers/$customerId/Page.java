package com.chaplin.roots.examples.workflow.pages.customers.$customerId;

import com.chaplin.roots.ActionEvent;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.Authorize;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.examples.workflow.CustomerRepository;
import com.chaplin.roots.html.Node;
import java.util.UUID;
import static com.chaplin.roots.html.Html.*;

@PageMetadata(title = "Customer record", stylesheets = "/workflow.css", robots = "noindex, nofollow")
public final class Page implements com.chaplin.roots.Page {
    private final CustomerRepository repository;
    private UUID id;
    public Page(CustomerRepository repository) { this.repository = repository; }
    @Override public Node render(PageContext context) {
        id = CustomerRepository.id(context.parameter("customerId"));
        var actor = context.identity().orElseThrow();
        var customer = repository.customer(actor, id);
        return section(a("All customers").href("/customers"),
                h1(customer.fields().company()), p("Saved version ", customer.version()).className("saved"),
                tag("dl", tag("dt", "Contact"), tag("dd", customer.fields().contact()), tag("dt", "Email"), tag("dd", customer.fields().email())),
                actor.hasRole("EDITOR") ? button("Edit customer").onClick(this, "edit").className("button") : null);
    }
    @ServerAction("edit") @Authorize("edit")
    private void edit(ActionEvent event) {
        event.redirect("/drafts/" + repository.create(event.identity().orElseThrow(), id).id());
    }
}
