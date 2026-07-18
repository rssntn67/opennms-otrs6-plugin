package it.arsinfo.opennms.plugins.otrs6.rest;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

@Path("opennms-otrs-6")
public interface WebhookHandler {

    @GET
    @Path("/ping")
    Response ping();

}
