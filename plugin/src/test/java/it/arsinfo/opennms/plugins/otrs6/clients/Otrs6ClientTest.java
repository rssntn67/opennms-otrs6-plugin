package it.arsinfo.opennms.plugins.otrs6.clients;

import it.arsinfo.opennms.plugins.otrs6.clients.otrs.*;
import org.junit.Before;
import org.junit.Test;
import org.opennms.integration.api.v1.ticketing.Ticket;
import org.opennms.integration.api.v1.ticketing.immutables.ImmutableTicket;

import java.math.BigInteger;
import java.util.List;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class Otrs6ClientTest {

    private GenericTicketConnectorInterface port;
    private Otrs6Client client;

    @Before
    public void setUp() {
        port = mock(GenericTicketConnectorInterface.class);
        client = new Otrs6Client(port, "testUser", "testPass");
    }

    @Test
    public void get_mapsTicketFieldsCorrectly() {
        OTRSTicketGetResponseTicket raw = new OTRSTicketGetResponseTicket();
        raw.setTicketID(BigInteger.valueOf(42));
        raw.setTitle("Test title");
        raw.setState("new");

        OTRSTicketGetResponse response = new OTRSTicketGetResponse();
        response.getTicket().add(raw);
        when(port.ticketGet(any())).thenReturn(response);

        Ticket ticket = client.get("42");

        assertThat(ticket.getId(), equalTo("42"));
        assertThat(ticket.getSummary(), equalTo("Test title"));
        assertThat(ticket.getState(), equalTo(Ticket.State.OPEN));
    }

    @Test
    public void get_returnsNullWhenResponseIsEmpty() {
        OTRSTicketGetResponse response = new OTRSTicketGetResponse();
        when(port.ticketGet(any())).thenReturn(response);

        Ticket ticket = client.get("99");

        assertThat(ticket, nullValue());
    }

    @Test
    public void get_mapsClosedStateCorrectly() {
        OTRSTicketGetResponseTicket raw = new OTRSTicketGetResponseTicket();
        raw.setTicketID(BigInteger.valueOf(7));
        raw.setTitle("Closed one");
        raw.setState("closed successful");

        OTRSTicketGetResponse response = new OTRSTicketGetResponse();
        response.getTicket().add(raw);
        when(port.ticketGet(any())).thenReturn(response);

        Ticket ticket = client.get("7");

        assertThat(ticket.getState(), equalTo(Ticket.State.CLOSED));
    }

    @Test
    public void getAll_returnsTicketsForEachId() {
        OTRSTicketSearchResponse searchResp = new OTRSTicketSearchResponse();
        searchResp.getTicketID().add(BigInteger.valueOf(1));
        searchResp.getTicketID().add(BigInteger.valueOf(2));
        when(port.ticketSearch(any())).thenReturn(searchResp);

        OTRSTicketGetResponseTicket t1 = new OTRSTicketGetResponseTicket();
        t1.setTicketID(BigInteger.ONE); t1.setTitle("T1"); t1.setState("new");
        OTRSTicketGetResponse r1 = new OTRSTicketGetResponse();
        r1.getTicket().add(t1);

        OTRSTicketGetResponseTicket t2 = new OTRSTicketGetResponseTicket();
        t2.setTicketID(BigInteger.TWO); t2.setTitle("T2"); t2.setState("closed successful");
        OTRSTicketGetResponse r2 = new OTRSTicketGetResponse();
        r2.getTicket().add(t2);

        when(port.ticketGet(any())).thenReturn(r1, r2);

        List<Ticket> all = client.getAll();

        assertThat(all.size(), equalTo(2));
        assertThat(all.get(0).getId(), equalTo("1"));
        assertThat(all.get(1).getState(), equalTo(Ticket.State.CLOSED));
    }

    @Test
    public void savaORUpdate_createsWhenIdIsNull() {
        OTRSTicketCreateResponse createResp = new OTRSTicketCreateResponse();
        createResp.setTicketID(BigInteger.valueOf(55));
        when(port.ticketCreate(any())).thenReturn(createResp);

        Ticket ticket = ImmutableTicket.newBuilder()
                .setSummary("New ticket")
                .setDetails("Details here")
                .setState(Ticket.State.OPEN)
                .build();

        String id = client.savaORUpdate(ticket);

        assertThat(id, equalTo("55"));
        verify(port).ticketCreate(any());
        verify(port, never()).ticketUpdate(any());
    }

    @Test
    public void savaORUpdate_updatesWhenIdIsPresent() {
        OTRSTicketUpdateResponse updateResp = new OTRSTicketUpdateResponse();
        updateResp.setTicketID(BigInteger.valueOf(10));
        when(port.ticketUpdate(any())).thenReturn(updateResp);

        Ticket ticket = ImmutableTicket.newBuilder()
                .setId("10")
                .setSummary("Updated title")
                .setDetails("Updated body")
                .setState(Ticket.State.OPEN)
                .build();

        String id = client.savaORUpdate(ticket);

        assertThat(id, equalTo("10"));
        verify(port).ticketUpdate(any());
        verify(port, never()).ticketCreate(any());
    }
}
