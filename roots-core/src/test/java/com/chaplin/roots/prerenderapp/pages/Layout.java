package com.chaplin.roots.prerenderapp.pages;

import com.chaplin.roots.HeadMetadata;
import com.chaplin.roots.OpenGraphMetadata;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.html.Node;

/** Shared metadata fixture proving that prerendering resolves layout inheritance. */
public final class Layout implements com.chaplin.roots.Layout {
    @Override
    public HeadMetadata headMetadata(PageContext context) {
        return new HeadMetadata().withOpenGraph(
                new OpenGraphMetadata().withSiteName("Roots Fixtures")
        );
    }

    @Override
    public Node render(PageContext context, Node children) {
        return children;
    }
}
