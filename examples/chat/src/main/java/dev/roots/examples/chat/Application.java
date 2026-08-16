package dev.roots.examples.chat;

import dev.roots.Roots;

public final class Application {
    private Application() {
    }

    public static void main(String[] arguments) {
        Roots.run(Application.class, arguments);
    }
}
