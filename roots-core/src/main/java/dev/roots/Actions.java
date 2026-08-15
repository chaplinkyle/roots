package dev.roots;

import dev.roots.annotation.ServerAction;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;

public final class Actions {
    private Actions() {
    }

    public static BoundAction bind(Object target, String actionName) {
        if (target == null) {
            throw new IllegalArgumentException("A server action target is required");
        }
        var matches = new ArrayList<Method>();
        for (var type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (var method : type.getDeclaredMethods()) {
                var annotation = method.getAnnotation(ServerAction.class);
                if (annotation == null) {
                    continue;
                }
                var declaredName = annotation.value().isBlank() ? method.getName() : annotation.value();
                if (declaredName.equals(actionName)) {
                    matches.add(method);
                }
            }
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No @ServerAction named '" + actionName + "' on " + target.getClass().getName());
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("More than one @ServerAction named '" + actionName + "' on " + target.getClass().getName());
        }
        var method = matches.getFirst();
        validate(method);
        if (!method.trySetAccessible()) {
            throw new IllegalArgumentException("Server action is not accessible: " + method);
        }
        var wireName = target.getClass().getSimpleName()
                + "-" + Integer.toHexString(System.identityHashCode(target))
                + ":" + actionName;
        return new BoundAction(wireName, event -> invoke(target, method, event));
    }

    private static void validate(Method method) {
        var parameters = method.getParameterTypes();
        if (method.getReturnType() != void.class
                || (parameters.length != 0
                && !(parameters.length == 1 && parameters[0] == ActionEvent.class))) {
            throw new IllegalArgumentException("@ServerAction methods must return void and accept zero arguments or one ActionEvent: " + method);
        }
    }

    private static void invoke(Object target, Method method, ActionEvent event) throws Exception {
        try {
            if (method.getParameterCount() == 0) {
                method.invoke(target);
            } else {
                method.invoke(target, event);
            }
        } catch (InvocationTargetException exception) {
            switch (exception.getCause()) {
                case Exception cause -> throw cause;
                case Error cause -> throw cause;
                case Throwable cause -> throw new RuntimeException(cause);
            }
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not invoke server action " + method, exception);
        }
    }

    public record BoundAction(String name, Action action) {
    }
}
