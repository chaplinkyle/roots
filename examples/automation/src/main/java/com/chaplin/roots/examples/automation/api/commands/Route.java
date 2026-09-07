package com.chaplin.roots.examples.automation.api.commands;

import com.chaplin.roots.*;
import com.chaplin.roots.annotation.*;
import com.chaplin.roots.examples.automation.Inbox;

@Stateless
@Authorize("automation")
public final class Route implements ApiRoute {
    private final Inbox inbox;
    public Route(Inbox inbox) { this.inbox = inbox; }
    @Override public Response post(Request request) throws Exception { return inbox.submit(request); }
}
