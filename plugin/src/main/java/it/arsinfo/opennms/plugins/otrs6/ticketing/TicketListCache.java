package it.arsinfo.opennms.plugins.otrs6.ticketing;

import org.opennms.integration.api.v1.ticketing.Ticket;

import java.util.Collections;
import java.util.List;

public class TicketListCache {
    private volatile List<Ticket> tickets = Collections.emptyList();

    public void set(List<Ticket> tickets) {
        this.tickets = Collections.unmodifiableList(tickets);
    }

    public List<Ticket> get() {
        return tickets;
    }
}
