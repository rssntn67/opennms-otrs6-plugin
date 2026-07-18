package it.arsinfo.opennms.plugins.otrs6.ticketing;

import it.arsinfo.opennms.plugins.otrs6.clients.ClientManager;
import it.arsinfo.opennms.plugins.otrs6.clients.OtrsClient;
import it.arsinfo.opennms.plugins.otrs6.connection.ConnectionManager;
import org.opennms.integration.api.v1.ticketing.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

public class OtrsTicketDao implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(OtrsTicketDao.class);

    private final ClientManager clientManager;
    private final ConnectionManager connectionManager;
    private final TicketListCache ticketListCache;

    public OtrsTicketDao(ClientManager clientManager, ConnectionManager connectionManager, TicketListCache ticketListCache) {
        this.clientManager = clientManager;
        this.connectionManager = connectionManager;
        this.ticketListCache = ticketListCache;
    }

    @Override
    public void run() {
        try {
            Optional<OtrsClient> client = clientManager.getOtrsClient(connectionManager);
            if (client.isEmpty()) {
                LOG.warn("No OTRS connection configured, skipping ticket cache warm-up");
                return;
            }
            List<Ticket> tickets = client.get().getAll();
            ticketListCache.set(tickets);
            LOG.debug("Warmed OTRS ticket cache with {} tickets", tickets.size());
        } catch (Exception e) {
            LOG.error("Failed to warm OTRS ticket cache", e);
        }
    }
}
