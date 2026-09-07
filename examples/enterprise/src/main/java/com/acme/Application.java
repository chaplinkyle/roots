package com.acme;

import com.chaplin.roots.Roots;
import com.chaplin.roots.RootsConfig;
import com.chaplin.roots.RootsCache;
import com.chaplin.roots.AuthorizationPolicy;
import com.chaplin.roots.Response;
import com.chaplin.roots.annotation.RootsApplication;

@RootsApplication
public final class Application {
    private Application() {
    }

    public static void main(String[] arguments) {
        Roots.run(config(arguments));
    }

    static RootsConfig config(String... arguments) {
        return RootsConfig.forApplication(Application.class)
                .environment()
                .cache(RootsCache.inMemory(2_000))
                .authorize("initialized-view", request -> request.session().get("visits").isPresent()
                        ? AuthorizationPolicy.allow()
                        : AuthorizationPolicy.deny(Response.json(403, "{\"error\":\"Forbidden\"}")))
                .arguments(arguments)
                .build();
    }
}
