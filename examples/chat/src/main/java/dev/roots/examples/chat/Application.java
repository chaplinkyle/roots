package dev.roots.examples.chat;

import dev.roots.Roots;
import dev.roots.annotation.RootsApplication;

@RootsApplication
public final class Application {
    private Application() {
    }

    public static void main(String[] arguments) {
        Roots.run(Application.class, arguments);
    }
}
