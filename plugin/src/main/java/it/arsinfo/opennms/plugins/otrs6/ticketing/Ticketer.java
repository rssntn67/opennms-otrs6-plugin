package it.arsinfo.opennms.plugins.otrs6.ticketing;

import it.arsinfo.opennms.plugins.otrs6.clients.OtrsClient;
import org.opennms.integration.api.v1.ticketing.Ticket;
import org.opennms.integration.api.v1.ticketing.TicketingPlugin;

public class Ticketer implements TicketingPlugin {
    private final OtrsClient client;

    public Ticketer(OtrsClient client) {
        this.client = client;
    }

    @Override
    public Ticket get(String ticketId) {
        return client.get(ticketId);
    }

    @Override
    public String saveOrUpdate(Ticket ticket) {
        return client.savaORUpdate(ticket);
    }
}
