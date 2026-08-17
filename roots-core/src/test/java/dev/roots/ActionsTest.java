package dev.roots;

import dev.roots.annotation.ServerAction;
import dev.roots.annotation.Authorize;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ActionsTest {
    @Test
    void bindsRenamedEventAndZeroArgumentActions() throws Exception {
        var target = new ValidTarget();
        var eventAction = Actions.bind(target, "save");
        var zeroAction = Actions.bind(target, "reset");
        var event = new ActionEvent("submit", Map.of(), new Session("actions"));

        eventAction.action().handle(event);
        assertEquals(event, target.event);
        assertTrue(eventAction.name().endsWith(":save"));

        zeroAction.action().handle(event);
        assertTrue(target.reset);
    }

    @Test
    void rejectsMissingDuplicateAndInvalidSignatures() {
        assertThrows(IllegalArgumentException.class, () -> Actions.bind(null, "save"));
        assertThrows(IllegalArgumentException.class, () -> Actions.bind(new ValidTarget(), "missing"));
        assertThrows(IllegalArgumentException.class, () -> Actions.bind(new DuplicateTarget(), "same"));
        assertThrows(IllegalArgumentException.class, () -> Actions.bind(new InvalidReturnTarget(), "invalid"));
        assertThrows(IllegalArgumentException.class, () -> Actions.bind(new InvalidParameterTarget(), "invalid"));
    }

    @Test
    void unwrapsCheckedExceptionsAndErrorsFromReflectedActions() {
        var checked = Actions.bind(new ThrowingTarget(), "checked");
        var fatal = Actions.bind(new ThrowingTarget(), "fatal");
        var event = new ActionEvent("click", Map.of(), new Session("throwing-actions"));

        var exception = assertThrows(IOException.class, () -> checked.action().handle(event));
        assertEquals("checked failure", exception.getMessage());
        var error = assertThrows(AssertionError.class, () -> fatal.action().handle(event));
        assertEquals("fatal failure", error.getMessage());
    }

    @Test
    void carriesOrderedAuthorizationPoliciesAndRejectsInvalidAnnotations() {
        var bound = Actions.bind(new AuthorizedTarget(), "secured");
        assertEquals(List.of("alpha", "beta"), bound.action().authorizationPolicies());
        assertThrows(UnsupportedOperationException.class,
                () -> bound.action().authorizationPolicies().add("no"));
        assertTrue(((Action) ignored -> { }).authorizationPolicies().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> Actions.bind(new EmptyAuthorizationTarget(), "empty"));
        assertThrows(IllegalArgumentException.class, () -> Actions.bind(new InvalidAuthorizationTarget(), "invalid"));
    }

    @Test
    void describesReflectedAndFunctionalActionsForTooling() {
        var reflected = Actions.describe(Actions.bind(new AuthorizedTarget(), "secured").action());
        assertEquals(AuthorizedTarget.class.getName(), reflected.targetType());
        assertEquals("secured", reflected.method());
        assertEquals(List.of("alpha", "beta"), reflected.authorizationPolicies());

        Action functional = ignored -> { };
        var described = Actions.describe(functional);
        assertEquals(functional.getClass().getName(), described.targetType());
        assertEquals("handle", described.method());
        assertTrue(described.authorizationPolicies().isEmpty());
    }

    private static final class ValidTarget {
        private ActionEvent event;
        private boolean reset;

        @ServerAction("save")
        private void handle(ActionEvent event) {
            this.event = event;
        }

        @ServerAction
        private void reset() {
            reset = true;
        }
    }

    private static final class DuplicateTarget {
        @ServerAction("same") private void first() { }
        @ServerAction("same") private void second() { }
    }

    private static final class InvalidReturnTarget {
        @ServerAction private String invalid() { return "no"; }
    }

    private static final class InvalidParameterTarget {
        @ServerAction private void invalid(String ignored) { }
    }

    private static final class ThrowingTarget {
        @ServerAction private void checked() throws IOException { throw new IOException("checked failure"); }
        @ServerAction private void fatal() { throw new AssertionError("fatal failure"); }
    }

    private static final class AuthorizedTarget {
        @Authorize({"alpha", "beta", "alpha"})
        @ServerAction
        private void secured() {
        }
    }

    private static final class EmptyAuthorizationTarget {
        @Authorize({})
        @ServerAction
        private void empty() {
        }
    }

    private static final class InvalidAuthorizationTarget {
        @Authorize("bad policy")
        @ServerAction
        private void invalid() {
        }
    }
}
