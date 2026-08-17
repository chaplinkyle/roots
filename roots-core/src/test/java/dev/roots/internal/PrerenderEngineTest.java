package dev.roots.internal;

import dev.roots.PageContext;
import dev.roots.InstanceFactory;
import dev.roots.RootsConfig;
import dev.roots.annotation.Authorize;
import dev.roots.annotation.Prerender;
import dev.roots.annotation.ServerAction;
import dev.roots.html.Node;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.roots.html.Html.button;
import static dev.roots.html.Html.div;
import static dev.roots.html.Html.portal;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class PrerenderEngineTest {
    @Test
    void rejectsActionsSessionStateAndPortals() {
        assertInvalid(ActionPage.class, "server actions");
        assertInvalid(SessionPage.class, "session state");
        assertInvalid(PortalPage.class, "Portal");
        assertInvalid(AsyncPage.class, "AsyncComponent");
    }

    @Test
    void rejectsNegativeIntervalsAndAuthorizationPolicies() {
        var negative = route(NegativePage.class, List.of());
        var negativeFailure = assertThrows(IllegalStateException.class, () -> PrerenderEngine.validateRoute(
                negative,
                NegativePage.class.getAnnotation(Prerender.class)
        ));
        assertTrue(negativeFailure.getMessage().contains("negative"), negativeFailure.getMessage());

        var authorized = route(AuthorizedPage.class, List.of("admin"));
        var authorizedFailure = assertThrows(IllegalStateException.class, () -> PrerenderEngine.validateRoute(
                authorized,
                AuthorizedPage.class.getAnnotation(Prerender.class)
        ));
        assertTrue(authorizedFailure.getMessage().contains("authorization"), authorizedFailure.getMessage());

    }

    @Test
    void preservesRenderFailuresAndReportsCleanupFailures() {
        var failingFactory = new InstanceFactory() {
            @Override
            public Object create(Class<?> type) {
                return type == RenderFailurePage.class ? new RenderFailurePage() : new PlainPage();
            }

            @Override
            public void destroy(Object instance) {
                throw new IllegalStateException("destroy failed");
            }
        };
        var config = RootsConfig.forApplication(PrerenderEngineTest.class)
                .instanceFactory(failingFactory)
                .build();

        var renderRoute = route(RenderFailurePage.class, List.of());
        var renderFailure = assertThrows(IllegalStateException.class, () -> PrerenderEngine.render(
                config, new ConventionRouter.PageMatch(renderRoute, Map.of()), "/", 0));
        assertEquals("render failed", renderFailure.getMessage());
        assertEquals(1, renderFailure.getSuppressed().length);
        assertEquals("destroy failed", renderFailure.getSuppressed()[0].getMessage());

        var plainRoute = route(PlainPage.class, List.of());
        var cleanupFailure = assertThrows(IllegalStateException.class, () -> PrerenderEngine.render(
                config, new ConventionRouter.PageMatch(plainRoute, Map.of()), "/", 0));
        assertTrue(cleanupFailure.getMessage().contains("Could not destroy"), cleanupFailure.getMessage());
        assertEquals("destroy failed", cleanupFailure.getCause().getMessage());
    }

    @Test
    void manifestParserRejectsMalformedAndUnsafeRows() {
        var config = RootsConfig.forApplication(PrerenderEngineTest.class).build();
        assertThrows(IllegalStateException.class,
                () -> PrerenderManifest.parse(config, "fixture", "wrong\n"));
        assertThrows(IllegalStateException.class, () -> PrerenderManifest.parse(
                config,
                "fixture",
                "ROOTS_PRERENDER\t1\t" + PrerenderEngineTest.class.getName()
                        + "\n/unsafe/../path\t0\tMETA-INF/roots/prerender/x/page.html\n"
        ));
        assertThrows(IllegalStateException.class, () -> PrerenderManifest.parse(
                config,
                "fixture",
                "ROOTS_PRERENDER\t1\t" + PrerenderEngineTest.class.getName()
                        + "\n/ok\t-1\tMETA-INF/roots/prerender/x/page.html\n"
        ));
        assertThrows(IllegalStateException.class, () -> PrerenderManifest.parse(
                config,
                "fixture",
                "ROOTS_PRERENDER\t1\t" + PrerenderEngineTest.class.getName()
                        + "\n/_roots/client.js\t0\tMETA-INF/roots/prerender/x/page.html\n"
        ));
    }

    private static void assertInvalid(Class<? extends dev.roots.Page> type, String expected) {
        var route = route(type, List.of());
        var match = new ConventionRouter.PageMatch(route, Map.of());
        var failure = assertThrows(IllegalStateException.class, () -> PrerenderEngine.render(
                RootsConfig.forApplication(PrerenderEngineTest.class).build(),
                match,
                "/",
                0
        ));
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }

    private static ConventionRouter.PageRoute route(
            Class<? extends dev.roots.Page> type,
            List<String> policies
    ) {
        return route(type, policies, "/");
    }

    private static ConventionRouter.PageRoute route(
            Class<? extends dev.roots.Page> type,
            List<String> policies,
            String path
    ) {
        return new ConventionRouter.PageRoute(RoutePattern.fromTemplate(path), type, List.of(), policies);
    }

    private static final class ActionPage implements dev.roots.Page {
        @Override
        public Node render(PageContext context) {
            return button("Mutate").onClick(this, "mutate");
        }

        @ServerAction
        private void mutate() {
        }
    }

    private static final class SessionPage implements dev.roots.Page {
        @Override
        public Node render(PageContext context) {
            context.session().put("personal", "value");
            return div("Personal");
        }
    }

    private static final class PortalPage implements dev.roots.Page {
        @Override
        public Node render(PageContext context) {
            return portal("modal", "Dialog");
        }
    }

    private static final class AsyncPage implements dev.roots.Page {
        @Override
        public Node render(PageContext context) {
            return dev.roots.AsyncComponent.of(() -> "ready", value -> div(value), div("Loading"));
        }
    }

    private static final class RenderFailurePage implements dev.roots.Page {
        @Override
        public Node render(PageContext context) {
            throw new IllegalStateException("render failed");
        }
    }

    private static final class PlainPage implements dev.roots.Page {
        @Override
        public Node render(PageContext context) {
            return div("Plain");
        }
    }

    @Prerender(revalidateSeconds = -1)
    private static final class NegativePage implements dev.roots.Page {
        @Override
        public Node render(PageContext context) {
            return div("Negative");
        }
    }

    @Prerender
    @Authorize("admin")
    private static final class AuthorizedPage implements dev.roots.Page {
        @Override
        public Node render(PageContext context) {
            return div("Authorized");
        }
    }

}
