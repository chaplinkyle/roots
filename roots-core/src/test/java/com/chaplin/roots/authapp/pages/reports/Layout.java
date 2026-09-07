package com.chaplin.roots.authapp.pages.reports;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.Authorize;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.main;

@Authorize("staff")
public final class Layout implements com.chaplin.roots.Layout {
    @Override
    public Node render(PageContext context, Node children) {
        return main(children).data("authorized-layout", "true");
    }
}
