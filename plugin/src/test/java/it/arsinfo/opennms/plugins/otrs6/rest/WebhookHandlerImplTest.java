package it.arsinfo.opennms.plugins.otrs6.rest;

import org.junit.Test;

import javax.ws.rs.core.Response;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

public class WebhookHandlerImplTest {

    private final WebhookHandlerImpl handler = new WebhookHandlerImpl();

    @Test
    public void ping_returnsOkStatus() {
        Response response = handler.ping();

        assertThat(response.getStatus(), equalTo(Response.Status.OK.getStatusCode()));
    }

    @Test
    public void ping_returnsPongEntity() {
        Response response = handler.ping();

        assertThat(response.getEntity(), equalTo("pong"));
    }
}
