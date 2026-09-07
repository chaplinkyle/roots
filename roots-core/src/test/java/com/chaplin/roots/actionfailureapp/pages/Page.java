package com.chaplin.roots.actionfailureapp.pages;

import com.chaplin.roots.ActionEvent;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.ValidationException;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;
import java.util.concurrent.atomic.AtomicInteger;
import static com.chaplin.roots.html.Html.*;

public final class Page implements com.chaplin.roots.Page {
    public static final AtomicInteger WRITES = new AtomicInteger();
    private String mode;
    private boolean completed;

    public Node render(PageContext context) {
        mode = context.query("mode").orElse("ok");
        if (completed && mode.equals("render")) throw new IllegalStateException("render failed after write");
        if (completed && mode.equals("render-validation")) throw ValidationException.field("value", "render failed");
        return main(button("Write").onClick(this, "write"));
    }

    @ServerAction
    private void write(ActionEvent event) throws Exception {
        if (mode.equals("validate") && event.value("value").isEmpty()) {
            throw ValidationException.field("value", "Required before writing");
        }
        WRITES.incrementAndGet();
        completed = true;
        switch (mode) {
            case "handler" -> throw new IllegalStateException("handler failed after write");
            case "error" -> throw new AssertionError("handler error after write");
            case "interrupt" -> throw new InterruptedException("handler interrupted after write");
            default -> { }
        }
    }
}
