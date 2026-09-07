package com.chaplin.roots.examples.kanban.pages;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.Authorize;
import com.chaplin.roots.html.Node;
import static com.chaplin.roots.html.Html.*;

@Authorize("read")
public final class Layout implements com.chaplin.roots.Layout {
    @Override public Node render(PageContext context,Node children) {
        boolean archived=context.query("archived").isPresent();
        return div(
            a("Skip to board").href("#content").className("skip"),
            aside(
                a(span("r").className("brand-symbol"),span("roots",small("Team workspace"))).href("/").className("brand"),
                div(span("K").className("workspace-icon"),div(strong("Kanban"),small("Engineering workspace"))).className("workspace-switch"),
                nav(a(span("▦"),"Board").href("/").className(!archived ? "selected" : ""),
                    a(span("▤"),"Archived tasks").href("/?archived=1").className(archived ? "selected" : "")).attr("aria-label","Workspace"),
                div(p("A little less busywork."),small("Keep the next step in sight.")).className("sidebar-note"),
                div(span("A").className("avatar"),div(strong(context.identity().orElseThrow().name()),
                    a("Sign out").href(context.connection().origin().resolve(context.mountPath()+"/../logout").normalize().toString()))).className("account")
            ).className("sidebar"),
            main(children).id("content").className("main")
        ).className("shell");
    }
}
