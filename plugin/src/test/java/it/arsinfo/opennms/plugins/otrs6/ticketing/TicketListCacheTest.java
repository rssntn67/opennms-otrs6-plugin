package it.arsinfo.opennms.plugins.otrs6.ticketing;

import org.junit.Before;
import org.junit.Test;
import org.opennms.integration.api.v1.ticketing.Ticket;
import org.opennms.integration.api.v1.ticketing.immutables.ImmutableTicket;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

public class TicketListCacheTest {

    private TicketListCache cache;

    @Before
    public void setUp() {
        cache = new TicketListCache();
    }

    @Test
    public void get_returnsEmptyListBeforeAnySet() {
        assertThat(cache.get(), equalTo(List.of()));
    }

    @Test
    public void get_returnsWhatWasSet() {
        Ticket ticket = ImmutableTicket.newBuilder().setId("1").setSummary("s").setState(Ticket.State.OPEN).build();

        cache.set(List.of(ticket));

        assertThat(cache.get(), equalTo(List.of(ticket)));
    }

    @Test
    public void get_returnsUnmodifiableList() {
        cache.set(new ArrayList<>());

        assertThrows(UnsupportedOperationException.class, () -> cache.get().add(
                ImmutableTicket.newBuilder().setId("1").setSummary("s").setState(Ticket.State.OPEN).build()));
    }
}
