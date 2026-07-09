package it.arsinfo.opennms.plugins.otrs6.clients;

import org.opennms.integration.api.v1.ticketing.Ticket;

import java.util.List;

public interface OtrsClient {
    List<Ticket> getAll();
    Ticket get(String ticketId);
    String savaORUpdate(Ticket ticket);
}
