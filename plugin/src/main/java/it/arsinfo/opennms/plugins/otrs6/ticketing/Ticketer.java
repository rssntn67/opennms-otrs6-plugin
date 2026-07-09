package it.arsinfo.opennms.plugins.otrs6.ticketing;

import it.arsinfo.opennms.plugins.otrs6.clients.OtrsClient;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.opennms.integration.api.v1.dao.AlarmDao;
import org.opennms.integration.api.v1.ticketing.Ticket;
import org.opennms.integration.api.v1.ticketing.TicketingPlugin;

import java.util.concurrent.ScheduledExecutorService;

public class Ticketer implements TicketingPlugin {
    @Reference
    AlarmDao alarmDao;

    ScheduledExecutorService scheduler;
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


    // add scheduler to get all ticket state on OTRS and then update
    // for each alarm with a ticket check opennms ticket status
    //
}
