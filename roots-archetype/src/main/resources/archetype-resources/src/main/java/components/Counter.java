package ${package}.components;

import dev.roots.Component;
import dev.roots.PageContext;
import dev.roots.State;
import dev.roots.annotation.ServerAction;
import dev.roots.annotation.ViewComponent;
import dev.roots.html.Node;

import static dev.roots.html.Html.button;
import static dev.roots.html.Html.div;
import static dev.roots.html.Html.strong;

@ViewComponent("counter")
public final class Counter implements Component {
    private final State<Integer> count = State.of(0);

    @Override
    public Node render(PageContext context) {
        return div(
                strong(count.get()),
                button("Increment in Java").type("button").onClick(this, "increment")
        ).className("counter");
    }

    @ServerAction
    private void increment() {
        count.update(value -> value + 1);
    }
}
