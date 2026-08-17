package dev.roots.invalidprerenderapp.pages.$slug;

import dev.roots.PageContext;
import dev.roots.annotation.Prerender;
import dev.roots.html.Node;
import dev.roots.invalidprerenderapp.DynamicPaths;

import static dev.roots.html.Html.main;

@Prerender(paths = DynamicPaths.class)
public final class Page implements dev.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main(context.parameter("slug"));
    }
}
