package com.acme;

import dev.roots.Roots;
import dev.roots.RootsConfig;
import dev.roots.RootsCache;
import dev.roots.AuthorizationPolicy;
import dev.roots.Response;
import dev.roots.annotation.RootsApplication;

@RootsApplication
public final class Application {
    private Application() {
    }

    public static void main(String[] arguments) {
        Roots.run(config(arguments));
    }

    static RootsConfig config(String... arguments) {
        return RootsConfig.forApplication(Application.class)
                .cache(RootsCache.inMemory(2_000))
                .authorize("initialized-view", request -> request.session().get("visits").isPresent()
                        ? AuthorizationPolicy.allow()
                        : AuthorizationPolicy.deny(Response.json(403, "{\"error\":\"Forbidden\"}")))
                .arguments(arguments)
                .build();
    }
}
