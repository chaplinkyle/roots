package dev.roots.browser.pages;

import dev.roots.Component;
import dev.roots.PageContext;
import dev.roots.annotation.ViewComponent;
import dev.roots.html.Node;

import static dev.roots.html.Html.fragment;

@ViewComponent("browser-inspection")
public final class InspectionProbe implements Component {
    @Override
    public Node render(PageContext context) {
        return fragment();
    }
}
