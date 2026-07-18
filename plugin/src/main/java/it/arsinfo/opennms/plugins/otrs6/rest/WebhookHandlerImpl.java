package it.arsinfo.opennms.plugins.otrs6.rest;

import javax.ws.rs.core.Response;

public class WebhookHandlerImpl implements WebhookHandler {

    @Override
    public Response ping() {
        return Response.ok("pong").build();
    }

}
