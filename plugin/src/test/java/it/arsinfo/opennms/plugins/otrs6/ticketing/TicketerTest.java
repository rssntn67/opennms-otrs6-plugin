package it.arsinfo.opennms.plugins.otrs6.ticketing;

import it.arsinfo.opennms.plugins.otrs6.clients.ClientManager;
import it.arsinfo.opennms.plugins.otrs6.clients.OtrsClient;
import it.arsinfo.opennms.plugins.otrs6.connection.ConnectionManager;
import org.junit.Before;
import org.junit.Test;
import org.opennms.integration.api.v1.ticketing.Ticket;
import org.opennms.integration.api.v1.ticketing.immutables.ImmutableTicket;

import java.util.Optional;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.*;

public class TicketerTest {

    private ClientManager clientManager;
    private ConnectionManager connectionManager;
    private OtrsClient otrsClient;
    private Ticketer ticketer;

    @Before
    public void setUp() {
        clientManager = mock(ClientManager.class);
        connectionManager = mock(ConnectionManager.class);
        otrsClient = mock(OtrsClient.class);
        ticketer = new Ticketer(clientManager, connectionManager);
    }

    @Test
    public void get_delegatesToResolvedClient() {
        when(clientManager.getOtrsClient(connectionManager)).thenReturn(Optional.of(otrsClient));
        Ticket ticket = ImmutableTicket.newBuilder().setId("1").setSummary("s").setState(Ticket.State.OPEN).build();
        when(otrsClient.get("1")).thenReturn(ticket);

        Ticket result = ticketer.get("1");

        assertThat(result, sameInstance(ticket));
    }

    @Test
    public void saveOrUpdate_delegatesToResolvedClient() {
        when(clientManager.getOtrsClient(connectionManager)).thenReturn(Optional.of(otrsClient));
        Ticket ticket = ImmutableTicket.newBuilder().setSummary("s").setState(Ticket.State.OPEN).build();
        when(otrsClient.savaORUpdate(ticket)).thenReturn("42");

        String id = ticketer.saveOrUpdate(ticket);

        assertThat(id, equalTo("42"));
    }

    @Test
    public void get_throwsWhenConnectionNotConfigured() {
        when(clientManager.getOtrsClient(connectionManager)).thenReturn(Optional.empty());

        try {
            ticketer.get("1");
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage(), equalTo("No OTRS connection configured"));
        }
    }

    @Test
    public void saveOrUpdate_throwsWhenConnectionNotConfigured() {
        when(clientManager.getOtrsClient(connectionManager)).thenReturn(Optional.empty());
        Ticket ticket = ImmutableTicket.newBuilder().setSummary("s").setState(Ticket.State.OPEN).build();

        try {
            ticketer.saveOrUpdate(ticket);
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage(), equalTo("No OTRS connection configured"));
        }
    }
}
