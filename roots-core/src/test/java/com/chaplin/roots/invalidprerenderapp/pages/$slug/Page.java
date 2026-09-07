package com.chaplin.roots.invalidprerenderapp.pages.$slug;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.Prerender;
import com.chaplin.roots.html.Node;
import com.chaplin.roots.invalidprerenderapp.DynamicPaths;

import static com.chaplin.roots.html.Html.main;

@Prerender(paths = DynamicPaths.class)
public final class Page implements com.chaplin.roots.Page {
    @Override
    public Node render(PageContext context) {
        return main(context.parameter("slug"));
    }
}
