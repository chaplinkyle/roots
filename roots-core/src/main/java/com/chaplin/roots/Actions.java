package com.chaplin.roots;

import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.annotation.Authorize;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Utilities for binding annotated Java methods to browser events. */
public final class Actions {
    // ClassValue follows application classloader lifetime during development reload.
    // Only successful method resolutions are cached; no component instances or
    // arbitrary missing names are retained by the cache.
    private static final ClassValue<ConcurrentHashMap<String, ResolvedAction>> METHODS = new ClassValue<>() {
        @Override
        protected ConcurrentHashMap<String, ResolvedAction> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private Actions() {
    }

    /** Finds and binds one named {@code @ServerAction} method.
     * @param target annotated object
     * @param actionName declared action name
     * @return stable binding
     * @throws IllegalArgumentException if the action is missing, ambiguous, or invalid */
    public static BoundAction bind(Object target, String actionName) {
        if (target == null) {
            throw new IllegalArgumentException("A server action target is required");
        }
        if (actionName == null) {
            throw new IllegalArgumentException("A server action name is required");
        }
        var resolved = METHODS.get(target.getClass()).computeIfAbsent(actionName,
                name -> resolve(target.getClass(), name));
        var wireName = target.getClass().getSimpleName()
                + "-" + Integer.toHexString(System.identityHashCode(target))
                + ":" + actionName;
        return new BoundAction(wireName, new ReflectedAction(target, resolved.method(), resolved.policies()));
    }

    private static ResolvedAction resolve(Class<?> targetType, String actionName) {
        var matches = new ArrayList<Method>();
        for (var type = targetType; type != null; type = type.getSuperclass()) {
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
            throw new IllegalArgumentException("No @ServerAction named '" + actionName + "' on " + targetType.getName());
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("More than one @ServerAction named '" + actionName + "' on " + targetType.getName());
        }
        var method = matches.getFirst();
        validate(method);
        if (!method.trySetAccessible()) {
            throw new IllegalArgumentException("Server action is not accessible: " + method);
        }
        return new ResolvedAction(method, policies(method));
    }

    private record ResolvedAction(Method method, List<String> policies) { }

    /**
     * Returns whether two bindings invoke the same target object and annotated method.
     *
     * @param first existing rendered binding
     * @param second candidate rendered binding
     * @return whether both bindings have the same handler identity
     */
    public static boolean sameBinding(Action first, Action second) {
        if (first == second) {
            return true;
        }
        return first instanceof ReflectedAction reflected && reflected.sameAs(second);
    }

    /** Describes an action binding without invoking it, for development tooling.
     * @param action rendered action binding
     * @return stable handler description for the current binding */
    public static ActionDescription describe(Action action) {
        if (action instanceof ReflectedAction reflected) {
            return new ActionDescription(
                    reflected.target.getClass().getName(),
                    reflected.method.getName(),
                    reflected.policies
            );
        }
        return new ActionDescription(
                action.getClass().getName(),
                "handle",
                action.authorizationPolicies()
        );
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

    private static List<String> policies(Method method) {
        var annotation = method.getAnnotation(Authorize.class);
        if (annotation == null) {
            return List.of();
        }
        var policies = new LinkedHashSet<String>();
        for (var policy : annotation.value()) {
            if (policy == null || !policy.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*")) {
                throw new IllegalArgumentException("Invalid authorization policy on " + method + ": " + policy);
            }
            policies.add(policy);
        }
        if (policies.isEmpty()) {
            throw new IllegalArgumentException("@Authorize must name at least one policy: " + method);
        }
        return List.copyOf(policies);
    }

    /** A wire name and its server-side handler.
     * @param name rendered wire name
     * @param action server-side handler */
    public record BoundAction(String name, Action action) {
    }

    /** Development-tooling description of a rendered action.
     * @param targetType binary handler target type
     * @param method handler method or functional entry point
     * @param authorizationPolicies policies checked before invocation */
    public record ActionDescription(String targetType, String method, List<String> authorizationPolicies) {
        /** Creates a defensively copied action description. */
        public ActionDescription {
            Objects.requireNonNull(targetType, "targetType");
            Objects.requireNonNull(method, "method");
            authorizationPolicies = List.copyOf(authorizationPolicies);
        }
    }

    private static final class ReflectedAction implements Action {
        private final Object target;
        private final Method method;
        private final List<String> policies;

        private ReflectedAction(Object target, Method method, List<String> policies) {
            this.target = target;
            this.method = method;
            this.policies = policies;
        }

        @Override
        public void handle(ActionEvent event) throws Exception {
            invoke(target, method, event);
        }

        @Override
        public List<String> authorizationPolicies() {
            return policies;
        }

        private boolean sameAs(Object candidate) {
            return candidate instanceof ReflectedAction other
                    && target == other.target
                    && method.equals(other.method);
        }
    }
}
