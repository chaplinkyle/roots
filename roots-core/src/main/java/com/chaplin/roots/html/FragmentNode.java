package com.chaplin.roots.html;

import java.util.List;

record FragmentNode(List<Node> children) implements Node {
    FragmentNode {
        children = List.copyOf(children);
    }
}
