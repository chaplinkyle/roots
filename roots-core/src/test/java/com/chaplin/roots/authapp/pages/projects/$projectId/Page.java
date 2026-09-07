package com.chaplin.roots.authapp.pages.projects.$projectId;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.Authorize;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.h1;

@Authorize("project")
public final class Page implements com.chaplin.roots.Page {
    @Override
    public Node render(PageContext context) {
        return h1("Project " + context.parameter("projectId"));
    }
}
