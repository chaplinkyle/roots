package dev.roots.html;

import dev.roots.ErrorBoundary;

import java.util.ArrayList;
import java.util.List;

public final class Html {
    private Html() {
    }

    public static Node text(Object value) {
        return new TextNode(value == null ? "" : String.valueOf(value));
    }

    public static Node unsafeHtml(String trustedHtml) {
        return new RawNode(trustedHtml);
    }

    public static Node fragment(Object... children) {
        var nodes = new ArrayList<Node>();
        for (var child : children) {
            addValue(nodes, child);
        }
        return new FragmentNode(nodes);
    }

    public static Node boundary(Node children, java.util.function.BiFunction<Throwable, dev.roots.PageContext, Node> fallback) {
        return new ErrorBoundary(children, fallback);
    }

    public static Element tag(String tag, Object... children) { return new Element(tag, children); }
    public static Element main(Object... children) { return tag("main", children); }
    public static Element section(Object... children) { return tag("section", children); }
    public static Element article(Object... children) { return tag("article", children); }
    public static Element div(Object... children) { return tag("div", children); }
    public static Element header(Object... children) { return tag("header", children); }
    public static Element footer(Object... children) { return tag("footer", children); }
    public static Element nav(Object... children) { return tag("nav", children); }
    public static Element aside(Object... children) { return tag("aside", children); }
    public static Element h1(Object... children) { return tag("h1", children); }
    public static Element h2(Object... children) { return tag("h2", children); }
    public static Element h3(Object... children) { return tag("h3", children); }
    public static Element p(Object... children) { return tag("p", children); }
    public static Element span(Object... children) { return tag("span", children); }
    public static Element strong(Object... children) { return tag("strong", children); }
    public static Element small(Object... children) { return tag("small", children); }
    public static Element ul(Object... children) { return tag("ul", children); }
    public static Element ol(Object... children) { return tag("ol", children); }
    public static Element li(Object... children) { return tag("li", children); }
    public static Element a(Object... children) { return tag("a", children); }
    public static Element button(Object... children) { return tag("button", children); }
    public static Element form(Object... children) { return tag("form", children); }
    public static Element label(Object... children) { return tag("label", children); }
    public static Element input() { return tag("input"); }
    public static Element textarea(Object... children) { return tag("textarea", children); }
    public static Element select(Object... children) { return tag("select", children); }
    public static Element option(Object... children) { return tag("option", children); }
    public static Element table(Object... children) { return tag("table", children); }
    public static Element thead(Object... children) { return tag("thead", children); }
    public static Element tbody(Object... children) { return tag("tbody", children); }
    public static Element tr(Object... children) { return tag("tr", children); }
    public static Element th(Object... children) { return tag("th", children); }
    public static Element td(Object... children) { return tag("td", children); }
    public static Element code(Object... children) { return tag("code", children); }
    public static Element pre(Object... children) { return tag("pre", children); }
    public static Element time(Object... children) { return tag("time", children); }
    public static Element br() { return tag("br"); }
    public static Element hr() { return tag("hr"); }

    public static Element link(String href, Object... children) {
        return a(children).href(href).attr("data-roots-link", true);
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
