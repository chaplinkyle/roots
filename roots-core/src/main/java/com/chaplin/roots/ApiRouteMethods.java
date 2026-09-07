package com.chaplin.roots;

import java.lang.reflect.Method;
import java.util.ArrayList;

/** Computes the canonical HTTP method contract of an {@link ApiRoute}. */
final class ApiRouteMethods {
    private static final ClassValue<Contract> CONTRACT = new ClassValue<>() {
        @Override
        protected Contract computeValue(Class<?> type) {
            var methods = new ArrayList<String>();
            var get = overrides(type, "get");
            if (get) {
                methods.add("GET");
            }
            if (get || overrides(type, "head")) {
                methods.add("HEAD");
            }
            addWhenOverridden(type, methods, "post", "POST");
            addWhenOverridden(type, methods, "put", "PUT");
            addWhenOverridden(type, methods, "patch", "PATCH");
            addWhenOverridden(type, methods, "delete", "DELETE");
            methods.add("OPTIONS");
            return new Contract(String.join(", ", methods), get);
        }
    };

    private ApiRouteMethods() {
    }

    static Response methodNotAllowed(ApiRoute route, String attemptedMethod) {
        return Response.methodNotAllowed(attemptedMethod)
                .withHeader("Allow", contract(route).allow());
    }

    static Response options(ApiRoute route) {
        return Response.noContent().withHeader("Allow", contract(route).allow());
    }

    static boolean supportsGet(ApiRoute route) {
        return contract(route).get();
    }

    private static Contract contract(ApiRoute route) {
        return CONTRACT.get(route.getClass());
    }

    private static void addWhenOverridden(
            Class<?> type,
            ArrayList<String> methods,
            String javaMethod,
            String httpMethod
    ) {
        if (overrides(type, javaMethod)) {
            methods.add(httpMethod);
        }
    }

    private static boolean overrides(Class<?> type, String name) {
        final Method method;
        try {
            method = type.getMethod(name, Request.class);
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("ApiRoute method contract is incomplete", exception);
        }
        return method.getDeclaringClass() != ApiRoute.class;
    }

    private record Contract(String allow, boolean get) {
    }
}
