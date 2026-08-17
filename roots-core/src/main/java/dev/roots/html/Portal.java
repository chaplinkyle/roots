package dev.roots.html;

import java.util.Objects;

/**
 * Renders Java-owned content into a browser host outside the live page root.
 * Portal children still participate in actions, lifecycle, refs, and rerenders.
 */
public final class Portal implements Node {
    private final String id;
    private final Node children;

    /**
     * Creates a portal.
     *
     * @param id stable portal identity within one rendered page
     * @param children portal content
     */
    public Portal(String id, Object... children) {
        Objects.requireNonNull(id, "id");
        if (!id.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}")) {
            throw new IllegalArgumentException(
                    "Portal IDs must start with a letter and contain at most 128 letters, digits, dots, colons, underscores, or hyphens"
            );
        }
        this.id = id;
        this.children = Html.fragment(children);
    }

    /** Returns the stable portal identity.
     * @return portal identifier */
    public String id() {
        return id;
    }

    /** Returns rendered portal children.
     * @return child tree */
    public Node children() {
        return children;
    }
}
