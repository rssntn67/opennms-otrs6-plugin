package it.arsinfo.opennms.plugins.otrs6.rest;

import it.arsinfo.opennms.plugins.otrs6.ticketing.TicketListCache;
import org.junit.Before;
import org.junit.Test;
import org.opennms.integration.api.v1.ticketing.Ticket;
import org.opennms.integration.api.v1.ticketing.immutables.ImmutableTicket;

import javax.ws.rs.core.Response;
import java.util.List;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

public class WebhookHandlerImplTest {

    private TicketListCache ticketListCache;
    private WebhookHandlerImpl handler;

    @Before
    public void setUp() {
        ticketListCache = new TicketListCache();
        handler = new WebhookHandlerImpl(ticketListCache);
    }

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

    @Test
    public void tickets_returnsEmptyListBeforeAnyDataIsCached() {
        Response response = handler.tickets();

        assertThat(response.getEntity(), equalTo(List.of()));
    }

    @Test
    public void tickets_returnsWhateverIsInTheCache() {
        Ticket ticket = ImmutableTicket.newBuilder().setId("1").setSummary("s").setState(Ticket.State.OPEN).build();
        ticketListCache.set(List.of(ticket));

        Response response = handler.tickets();

        assertThat(response.getEntity(), equalTo(List.of(ticket)));
    }
}
