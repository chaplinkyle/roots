package com.chaplin.roots.browser.pages;

import com.chaplin.roots.Component;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.ViewComponent;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.fragment;

@ViewComponent("browser-inspection")
public final class InspectionProbe implements Component {
    @Override
    public Node render(PageContext context) {
        return fragment();
    }
}
