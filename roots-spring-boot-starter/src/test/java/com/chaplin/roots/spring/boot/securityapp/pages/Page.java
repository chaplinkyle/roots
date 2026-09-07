package com.chaplin.roots.spring.boot.securityapp.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.main;

public final class Page implements com.chaplin.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main("Public identity: " + context.identity().map(identity -> identity.name()).orElse("anonymous"));
    }
}
