package it.arsinfo.opennms.plugins.otrs6.ticketing;

import it.arsinfo.opennms.plugins.otrs6.clients.ClientManager;
import it.arsinfo.opennms.plugins.otrs6.clients.OtrsClient;
import it.arsinfo.opennms.plugins.otrs6.connection.ConnectionManager;
import org.junit.Before;
import org.junit.Test;
import org.opennms.integration.api.v1.ticketing.Ticket;
import org.opennms.integration.api.v1.ticketing.immutables.ImmutableTicket;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class OtrsTicketDaoTest {

    private ClientManager clientManager;
    private ConnectionManager connectionManager;
    private OtrsClient otrsClient;
    private TicketListCache ticketListCache;
    private OtrsTicketDao dao;

    @Before
    public void setUp() {
        clientManager = mock(ClientManager.class);
        connectionManager = mock(ConnectionManager.class);
        otrsClient = mock(OtrsClient.class);
        ticketListCache = mock(TicketListCache.class);
        dao = new OtrsTicketDao(clientManager, connectionManager, ticketListCache);

        when(clientManager.getOtrsClient(connectionManager)).thenReturn(Optional.of(otrsClient));
    }

    @Test
    public void run_skipsWhenNoConnectionConfigured() {
        when(clientManager.getOtrsClient(connectionManager)).thenReturn(Optional.empty());

        dao.run();

        verify(otrsClient, never()).getAll();
        verify(ticketListCache, never()).set(any());
    }

    @Test
    public void run_callsGetAllOnceWhenConnectionConfigured() {
        Ticket ticket = ImmutableTicket.newBuilder().setId("1").setSummary("s").setState(Ticket.State.OPEN).build();
        when(otrsClient.getAll()).thenReturn(List.of(ticket));

        dao.run();

        verify(otrsClient, times(1)).getAll();
    }

    @Test
    public void run_publishesFetchedTicketsToCache() {
        Ticket ticket = ImmutableTicket.newBuilder().setId("1").setSummary("s").setState(Ticket.State.OPEN).build();
        when(otrsClient.getAll()).thenReturn(List.of(ticket));

        dao.run();

        verify(ticketListCache, times(1)).set(List.of(ticket));
    }

    @Test
    public void run_doesNotPropagateExceptionFromGetAll() {
        when(otrsClient.getAll()).thenThrow(new RuntimeException("boom"));

        dao.run();

        verify(otrsClient, times(1)).getAll();
        verify(ticketListCache, never()).set(any());
    }

    @Test
    public void run_doesNotPropagateExceptionFromClientResolution() {
        when(clientManager.getOtrsClient(connectionManager)).thenThrow(new RuntimeException("vault unavailable"));

        dao.run();

        verify(otrsClient, never()).getAll();
        verify(ticketListCache, never()).set(any());
    }
}
