package com.chaplin.roots.examples.workflow.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.Authorize;
import com.chaplin.roots.html.Node;
import static com.chaplin.roots.html.Html.*;

@Authorize("read")
public final class Layout implements com.chaplin.roots.Layout {
    @Override public Node render(PageContext context, Node children) {
        boolean editor = context.identity().orElseThrow().hasRole("EDITOR");
        return div(
                a("Skip to content").href("#content").className("skip"),
                header(a("Customer operations").href("/customers").className("brand"),
                        nav(a("Directory").href("/customers"),
                                editor ? a("My drafts").href("/drafts") : null,
                                a("Sign out").href(context.connection().origin()
                                        .resolve(context.mountPath() + "/../logout").normalize().toString()))
                                .attr("aria-label", "Main navigation")),
                main(children).id("content"),
                footer(editor ? "Editor access" : "Read-only access",
                        " · Saved drafts remain available when you sign in again.")
        ).className("workspace");
    }
}
