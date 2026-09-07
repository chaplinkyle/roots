package ${package}.components;

import com.chaplin.roots.Component;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.State;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.annotation.ViewComponent;
import com.chaplin.roots.html.Node;

import static com.chaplin.roots.html.Html.button;
import static com.chaplin.roots.html.Html.div;
import static com.chaplin.roots.html.Html.strong;

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
