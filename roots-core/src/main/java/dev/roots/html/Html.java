package dev.roots.html;

import dev.roots.ErrorBoundary;
import dev.roots.Action;
import dev.roots.Actions;

import java.util.ArrayList;
import java.util.List;

/** Factory methods for constructing immutable Roots HTML node trees. */
public final class Html {
    private Html() {
    }

    /**
     * Creates an escaped text node.
     * @param value value to render, or {@code null} for an empty string
     * @return escaped text node
     */
    public static Node text(Object value) {
        return new TextNode(value == null ? "" : String.valueOf(value));
    }

    /**
     * Creates a raw HTML node from trusted markup.
     * @param trustedHtml trusted markup that must already be safe to emit
     * @return raw HTML node
     */
    public static Node unsafeHtml(String trustedHtml) {
        return new RawNode(trustedHtml);
    }

    /**
     * Groups values without adding a wrapping element.
     * @param children child nodes, iterables, or values converted to text
     * @return fragment node
     */
    public static Node fragment(Object... children) {
        var nodes = new ArrayList<Node>();
        for (var child : children) {
            addValue(nodes, child);
        }
        return new FragmentNode(nodes);
    }

    /**
     * Creates an error boundary around a node tree.
     * @param children protected content
     * @param fallback renderer used if a descendant fails
     * @return error-boundary node
     */
    public static Node boundary(Node children, java.util.function.BiFunction<Throwable, dev.roots.PageContext, Node> fallback) {
        return new ErrorBoundary(children, fallback);
    }

    /**
     * Creates content mounted outside the live page root by the Roots browser runtime.
     * @param id unique portal mount identifier
     * @param children portal content
     * @return portal node
     */
    public static Portal portal(String id, Object... children) { return new Portal(id, children); }

    /**
     * Creates an accessible native modal whose dismissal is handled by a server action.
     * @param id stable portal/modal identity
     * @param title visible accessible title
     * @param dismissActionName wire action name used for Escape and backdrop dismissal
     * @param dismissAction authoritative close action
     * @param children dialog content rendered after the title
     * @return configurable modal node
     */
    public static Modal modal(
            String id,
            String title,
            String dismissActionName,
            Action dismissAction,
            Object... children
    ) {
        return new Modal(id, title, dismissActionName, dismissAction, children);
    }

    /**
     * Creates an accessible native modal bound to an annotated server action.
     * @param id stable portal/modal identity
     * @param title visible accessible title
     * @param target annotated action target
     * @param annotatedAction annotated method name
     * @param children dialog content rendered after the title
     * @return configurable modal node
     */
    public static Modal modal(
            String id,
            String title,
            Object target,
            String annotatedAction,
            Object... children
    ) {
        var bound = Actions.bind(target, annotatedAction);
        return modal(id, title, bound.name(), bound.action(), children);
    }

    /**
     * Creates an arbitrary HTML element.
     * @param tag lowercase HTML tag name
     * @param children element content
     * @return configurable element
     */
    public static Element tag(String tag, Object... children) { return new Element(tag, children); }
    /** Creates a main landmark.
     * @param children element content
     * @return a {@code main} element */
    public static Element main(Object... children) { return tag("main", children); }
    /** Creates a document section.
     * @param children element content
     * @return a {@code section} element */
    public static Element section(Object... children) { return tag("section", children); }
    /** Creates an article.
     * @param children element content
     * @return an {@code article} element */
    public static Element article(Object... children) { return tag("article", children); }
    /** Creates a generic block container.
     * @param children element content
     * @return a {@code div} element */
    public static Element div(Object... children) { return tag("div", children); }
    /** Creates a header landmark.
     * @param children element content
     * @return a {@code header} element */
    public static Element header(Object... children) { return tag("header", children); }
    /** Creates a footer landmark.
     * @param children element content
     * @return a {@code footer} element */
    public static Element footer(Object... children) { return tag("footer", children); }
    /** Creates a navigation landmark.
     * @param children element content
     * @return a {@code nav} element */
    public static Element nav(Object... children) { return tag("nav", children); }
    /** Creates complementary content.
     * @param children element content
     * @return an {@code aside} element */
    public static Element aside(Object... children) { return tag("aside", children); }
    /** Creates a dialog container.
     * @param children element content
     * @return a {@code dialog} element */
    public static Element dialog(Object... children) { return tag("dialog", children); }
    /** Creates a level-one heading.
     * @param children element content
     * @return an {@code h1} element */
    public static Element h1(Object... children) { return tag("h1", children); }
    /** Creates a level-two heading.
     * @param children element content
     * @return an {@code h2} element */
    public static Element h2(Object... children) { return tag("h2", children); }
    /** Creates a level-three heading.
     * @param children element content
     * @return an {@code h3} element */
    public static Element h3(Object... children) { return tag("h3", children); }
    /** Creates a paragraph.
     * @param children element content
     * @return a paragraph element */
    public static Element p(Object... children) { return tag("p", children); }
    /** Creates an inline container.
     * @param children element content
     * @return a {@code span} element */
    public static Element span(Object... children) { return tag("span", children); }
    /** Creates strongly emphasized content.
     * @param children element content
     * @return a {@code strong} element */
    public static Element strong(Object... children) { return tag("strong", children); }
    /** Creates side-comment text.
     * @param children element content
     * @return a {@code small} element */
    public static Element small(Object... children) { return tag("small", children); }
    /** Creates an unordered list.
     * @param children list items
     * @return an unordered-list element */
    public static Element ul(Object... children) { return tag("ul", children); }
    /** Creates an ordered list.
     * @param children list items
     * @return an ordered-list element */
    public static Element ol(Object... children) { return tag("ol", children); }
    /** Creates a list item.
     * @param children item content
     * @return a list-item element */
    public static Element li(Object... children) { return tag("li", children); }
    /** Creates an anchor.
     * @param children link content
     * @return an anchor element */
    public static Element a(Object... children) { return tag("a", children); }
    /** Creates a button.
     * @param children button content
     * @return a button element */
    public static Element button(Object... children) { return tag("button", children); }
    /** Creates a form.
     * @param children form controls
     * @return a form element */
    public static Element form(Object... children) { return tag("form", children); }
    /** Creates a form label.
     * @param children label content
     * @return a label element */
    public static Element label(Object... children) { return tag("label", children); }
    /** Creates an input control.
     * @return an input element */
    public static Element input() { return tag("input"); }
    /** Creates a responsive local image served through Roots' image optimizer.
     * @param source root-relative png or jpeg path under {@code public/}
     * @param alternativeText accessible alternative text, which may be empty for decorative images
     * @param width intrinsic display width
     * @param height intrinsic display height
     * @return configurable optimized image */
    public static OptimizedImage image(String source, String alternativeText, int width, int height) {
        return new OptimizedImage(source, alternativeText, width, height);
    }
    /** Creates a multiline input control.
     * @param children fallback content
     * @return a textarea element */
    public static Element textarea(Object... children) { return tag("textarea", children); }
    /** Creates a selection control.
     * @param children option elements
     * @return a select element */
    public static Element select(Object... children) { return tag("select", children); }
    /** Creates a selection option.
     * @param children option label
     * @return an option element */
    public static Element option(Object... children) { return tag("option", children); }
    /** Creates a table.
     * @param children table sections or rows
     * @return a table element */
    public static Element table(Object... children) { return tag("table", children); }
    /** Creates a table header group.
     * @param children header rows
     * @return a table-head element */
    public static Element thead(Object... children) { return tag("thead", children); }
    /** Creates a table body group.
     * @param children body rows
     * @return a table-body element */
    public static Element tbody(Object... children) { return tag("tbody", children); }
    /** Creates a table row.
     * @param children cells
     * @return a table-row element */
    public static Element tr(Object... children) { return tag("tr", children); }
    /** Creates a table heading cell.
     * @param children heading content
     * @return a table-heading cell */
    public static Element th(Object... children) { return tag("th", children); }
    /** Creates a table data cell.
     * @param children cell content
     * @return a table-data cell */
    public static Element td(Object... children) { return tag("td", children); }
    /** Creates inline code content.
     * @param children code content
     * @return an inline-code element */
    public static Element code(Object... children) { return tag("code", children); }
    /** Creates preformatted text.
     * @param children preformatted content
     * @return a preformatted-text element */
    public static Element pre(Object... children) { return tag("pre", children); }
    /** Creates a machine-readable time value.
     * @param children time label
     * @return a time element */
    public static Element time(Object... children) { return tag("time", children); }
    /** Creates a line break.
     * @return a line-break element */
    public static Element br() { return tag("br"); }
    /** Creates a thematic break.
     * @return a thematic-break element */
    public static Element hr() { return tag("hr"); }

    /**
     * Creates an initially hidden target for field-specific validation messages.
     *
     * @param field submitted form field name
     * @return accessible validation message element
     */
    public static Element validationMessage(String field) {
        return span()
                .attr("data-roots-validation-for", validationField(field))
                .attr("hidden", true)
                .aria("live", "polite");
    }

    /**
     * Creates an initially hidden alert target for a form validation summary.
     *
     * @return accessible validation summary element
     */
    public static Element validationSummary() {
        return div()
                .attr("data-roots-validation-summary", true)
                .attr("hidden", true)
                .attr("role", "alert");
    }

    /**
     * Creates a client-side navigation link.
     * @param href destination path or URL
     * @param children link content
     * @return anchor marked for Roots navigation
     */
    public static Element link(String href, Object... children) {
        return a(children).href(href).attr("data-roots-link", true);
    }

    private static String validationField(String field) {
        if (field == null || field.isBlank() || field.length() > 128
                || field.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "Validation field names must be printable, non-blank, and at most 128 characters"
            );
        }
        return field;
    }

    static void addValue(List<Node> nodes, Object value) {
        switch (value) {
            case null -> { }
            case Node node -> nodes.add(node);
            case Iterable<?> iterable -> iterable.forEach(item -> addValue(nodes, item));
            default -> nodes.add(new TextNode(String.valueOf(value)));
        }
    }
}
