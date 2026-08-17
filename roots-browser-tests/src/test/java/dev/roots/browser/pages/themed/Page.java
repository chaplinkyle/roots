package dev.roots.browser.pages.themed;

import dev.roots.HeadMetadata;
import dev.roots.OpenGraphMetadata;
import dev.roots.PageContext;
import dev.roots.annotation.PageMetadata;
import dev.roots.annotation.ServerAction;
import dev.roots.html.Node;

import static dev.roots.html.Html.h1;
import static dev.roots.html.Html.button;
import static dev.roots.html.Html.link;
import static dev.roots.html.Html.div;
import static dev.roots.html.Html.aside;
import static dev.roots.html.Html.portal;

@PageMetadata(title = "Roots themed fixture", stylesheets = "/theme.css")
public final class Page implements dev.roots.Page {
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
