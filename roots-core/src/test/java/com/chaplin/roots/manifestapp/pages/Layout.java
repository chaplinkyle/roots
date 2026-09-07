package com.chaplin.roots.manifestapp.pages;

import com.chaplin.roots.PageContext;
import com.chaplin.roots.html.Node;

public final class Layout implements com.chaplin.roots.Layout {
    @Override
    public Node render(PageContext context, Node children) {
        return children;
    }
}
