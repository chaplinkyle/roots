package com.chaplin.roots.examples.workflow.pages;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.html.Node;
import static com.chaplin.roots.html.Html.*;
@PageMetadata(title = "Customer operations", stylesheets = "/workflow.css", robots = "noindex, nofollow")
public final class Page implements com.chaplin.roots.Page {
    @Override public Node render(PageContext context) {
        return section(h1("Customer operations"), p("Maintain customer records and return to your saved work."),
                a("Open directory").href("/customers").className("button"));
    }
}
