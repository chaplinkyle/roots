package com.chaplin.roots.browser.pages.themed;

import com.chaplin.roots.HeadMetadata;
import com.chaplin.roots.OpenGraphMetadata;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.h1;
import static com.chaplin.roots.html.Html.button;
import static com.chaplin.roots.html.Html.link;
import static com.chaplin.roots.html.Html.div;
import static com.chaplin.roots.html.Html.aside;
import static com.chaplin.roots.html.Html.portal;

@PageMetadata(title = "Roots themed fixture", stylesheets = "/theme.css")
public final class Page implements com.chaplin.roots.Page {
    private boolean alternateHead;

    @Override
    public HeadMetadata headMetadata(PageContext context) {
        return new HeadMetadata()
                .withCanonical(alternateHead ? "/themed/updated" : "/themed")
                .withRobots(alternateHead ? "noindex, nofollow" : "index, follow")
                .withThemeColor(alternateHead ? "#553399" : "#114477")
                .withOpenGraph(new OpenGraphMetadata()
                        .withType("website")
                        .withImage(alternateHead ? "/updated-preview.png" : "/theme-preview.png")
                        .withImageAlt(alternateHead ? "Updated theme preview" : "Theme preview"));
    }

    @Override
    public Node render(PageContext context) {
        return div(
                h1(
                        "Themed browser fixture",
                        link("/", "Home").id("home-link").viewTransition()
                ),
                button("Update metadata").id("head-update").onClick(this, "toggleHead"),
                portal("themed-status", aside("Themed portal mounted")
                        .id("themed-portal").aria("label", "Theme status"))
        );
    }

    @ServerAction
    private void toggleHead() {
        alternateHead = !alternateHead;
    }
}
