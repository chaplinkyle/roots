package com.chaplin.roots;

import java.util.List;

/** A server-side handler bound to a browser event in the rendered tree. */
@FunctionalInterface
public interface Action {
    /**
     * Handles one browser event on the server.
     *
     * @param event submitted browser event and session context
     * @throws Exception when application action code fails
     */
    void handle(ActionEvent event) throws Exception;

    /** Returns the authorization policies required immediately before execution.
     * @return required named policies */
    default List<String> authorizationPolicies() {
        return List.of();
    }
}
