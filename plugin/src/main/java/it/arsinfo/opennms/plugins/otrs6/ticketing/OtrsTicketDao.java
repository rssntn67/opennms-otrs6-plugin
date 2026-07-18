package it.arsinfo.opennms.plugins.otrs6.ticketing;

import it.arsinfo.opennms.plugins.otrs6.clients.ClientManager;
import it.arsinfo.opennms.plugins.otrs6.clients.OtrsClient;
import it.arsinfo.opennms.plugins.otrs6.connection.ConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class OtrsTicketDao implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(OtrsTicketDao.class);

    private final ClientManager clientManager;
    private final ConnectionManager connectionManager;

    public OtrsTicketDao(ClientManager clientManager, ConnectionManager connectionManager) {
        this.clientManager = clientManager;
        this.connectionManager = connectionManager;
    }

    @Override
    public void run() {
        try {
            Optional<OtrsClient> client = clientManager.getOtrsClient(connectionManager);
            if (client.isEmpty()) {
                LOG.warn("No OTRS connection configured, skipping ticket cache warm-up");
                return;
            }
            int count = client.get().getAll().size();
            LOG.debug("Warmed OTRS ticket cache with {} tickets", count);
        } catch (Exception e) {
            LOG.error("Failed to warm OTRS ticket cache", e);
        }
    }
}
