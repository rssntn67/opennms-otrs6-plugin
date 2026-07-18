package it.arsinfo.opennms.plugins.otrs6.rest;

import it.arsinfo.opennms.plugins.otrs6.ticketing.TicketListCache;

import javax.ws.rs.core.Response;

public class WebhookHandlerImpl implements WebhookHandler {

    private final TicketListCache ticketListCache;

    public WebhookHandlerImpl(TicketListCache ticketListCache) {
        this.ticketListCache = ticketListCache;
    }

    @Override
    public Response ping() {
        return Response.ok("pong").build();
    }

    @Override
    public Response tickets() {
        return Response.ok(ticketListCache.get()).build();
    }

}
