package dev.roots.spring.boot.securityapp.pages;

import dev.roots.PageContext;
import dev.roots.html.Node;

import static dev.roots.html.Html.main;

public final class Page implements dev.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main("Public identity: " + context.identity().map(identity -> identity.name()).orElse("anonymous"));
    }
}
