package it.arsinfo.opennms.plugins.otrs6.ticketing;

import it.arsinfo.opennms.plugins.otrs6.clients.ClientManager;
import it.arsinfo.opennms.plugins.otrs6.clients.OtrsClient;
import it.arsinfo.opennms.plugins.otrs6.connection.ConnectionManager;
import org.opennms.integration.api.v1.dao.AlarmDao;
import org.opennms.integration.api.v1.model.Alarm;
import org.opennms.integration.api.v1.ticketing.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class AlarmTicketUpdater implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(AlarmTicketUpdater.class);

    private final ClientManager clientManager;
    private final ConnectionManager connectionManager;
    private final AlarmDao alarmDao;

    public AlarmTicketUpdater(ClientManager clientManager, ConnectionManager connectionManager, AlarmDao alarmDao) {
        this.clientManager = clientManager;
        this.connectionManager = connectionManager;
        this.alarmDao = alarmDao;
    }

    @Override
    public void run() {
        Optional<OtrsClient> client = clientManager.getOtrsClient(connectionManager);
        if (client.isEmpty()) {
            LOG.warn("No OTRS connection configured, skipping ticket state update");
            return;
        }
        for (Alarm alarm : alarmDao.getAlarms()) {
            updateAlarmTicketState(client.get(), alarm);
        }
    }

    private void updateAlarmTicketState(OtrsClient client, Alarm alarm) {
        String ticketId = alarm.getTicketId();
        if (ticketId == null || ticketId.isBlank()) {
            return;
        }
        try {
            Ticket ticket = client.get(ticketId);
            if (ticket == null) {
                LOG.warn("Ticket {} referenced by alarm {} no longer exists in OTRS", ticketId, alarm.getId());
                return;
            }
            if (ticket.getState() != alarm.getTicketState()) {
                alarmDao.setTicketState(ticket.getState(), alarm.getId());
            }
        } catch (Exception e) {
            LOG.error("Failed to update ticket state for alarm {} (ticket {})", alarm.getId(), ticketId, e);
        }
    }
}
