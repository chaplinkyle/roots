package dev.roots.prerenderapp.pages;

import dev.roots.HeadMetadata;
import dev.roots.OpenGraphMetadata;
import dev.roots.PageContext;
import dev.roots.html.Node;

/** Shared metadata fixture proving that prerendering resolves layout inheritance. */
public final class Layout implements dev.roots.Layout {
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
