package ${package};

import com.chaplin.roots.Roots;
import com.chaplin.roots.annotation.RootsApplication;

@RootsApplication
public final class Application {
    private Application() {
    }

    public static void main(String[] arguments) {
        Roots.run(Application.class, arguments);
    }
}
