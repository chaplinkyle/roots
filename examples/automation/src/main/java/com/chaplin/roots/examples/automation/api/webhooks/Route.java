package com.chaplin.roots.examples.automation.api.webhooks;

import com.chaplin.roots.*;
import com.chaplin.roots.annotation.Stateless;
import com.chaplin.roots.examples.automation.Inbox;

/** Authentication is the Standard Webhooks signature checked before accepting the command. */
@Stateless
public final class Route implements ApiRoute {
    private final Inbox inbox;
    public Route(Inbox inbox) { this.inbox = inbox; }
    @Override public Response post(Request request) throws Exception { return inbox.ingest(request); }
}
