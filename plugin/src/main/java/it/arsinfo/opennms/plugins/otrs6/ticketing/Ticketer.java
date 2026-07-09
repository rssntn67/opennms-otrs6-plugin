package it.arsinfo.opennms.plugins.otrs6.ticketing;

import it.arsinfo.opennms.plugins.otrs6.clients.ClientManager;
import it.arsinfo.opennms.plugins.otrs6.clients.OtrsClient;
import it.arsinfo.opennms.plugins.otrs6.connection.ConnectionManager;
import org.opennms.integration.api.v1.ticketing.Ticket;
import org.opennms.integration.api.v1.ticketing.TicketingPlugin;

public class Ticketer implements TicketingPlugin {
    private final ClientManager clientManager;
    private final ConnectionManager connectionManager;

    public Ticketer(ClientManager clientManager, ConnectionManager connectionManager) {
        this.clientManager = clientManager;
        this.connectionManager = connectionManager;
    }

    @Override
    public Ticket get(String ticketId) {
        return client().get(ticketId);
    }

    @Override
    public String saveOrUpdate(Ticket ticket) {
        return client().savaORUpdate(ticket);
    }

    private OtrsClient client() {
        var connection = connectionManager.getConnection()
                .orElseThrow(() -> new IllegalStateException("No OTRS connection configured"));
        return clientManager.getOtrsClient(ClientManager.asClientCredentials(connection));
    }
}
