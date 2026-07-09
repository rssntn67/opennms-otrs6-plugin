package it.arsinfo.opennms.plugins.otrs6.clients;

import it.arsinfo.opennms.plugins.otrs6.clients.otrs.*;
import org.opennms.integration.api.v1.ticketing.Ticket;
import org.opennms.integration.api.v1.ticketing.immutables.ImmutableTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.ws.BindingProvider;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class Otrs6Client implements OtrsClient {

    private static final Logger LOG = LoggerFactory.getLogger(Otrs6Client.class);

    private final GenericTicketConnectorInterface port;
    private final String otrsUser;
    private final String otrsPassword;
    private final ObjectFactory factory = new ObjectFactory();

    public Otrs6Client(String otrsUrl, String otrsUser, String otrsPassword) {
        this.otrsUser = otrsUser;
        this.otrsPassword = otrsPassword;
        try {
            GenericTicketConnector service = new GenericTicketConnector(new URL(otrsUrl));
            this.port = service.getGenericTicketConnectorEndPoint();
            ((BindingProvider) port).getRequestContext()
                    .put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, otrsUrl);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid OTRS URL: " + otrsUrl, e);
        }
    }

    Otrs6Client(GenericTicketConnectorInterface port, String otrsUser, String otrsPassword) {
        this.port = port;
        this.otrsUser = otrsUser;
        this.otrsPassword = otrsPassword;
    }

    @Override
    public List<Ticket> getAll() {
        OTRSTicketSearch search = factory.createOTRSTicketSearch();
        search.getContent().add(factory.createOTRSTicketSearchUserLogin(otrsUser));
        search.getContent().add(factory.createOTRSTicketSearchPassword(otrsPassword));

        OTRSTicketSearchResponse response = port.ticketSearch(search);
        List<Ticket> tickets = new ArrayList<>();
        for (BigInteger id : response.getTicketID()) {
            Ticket t = get(id.toString());
            if (t != null) {
                tickets.add(t);
            }
        }
        return tickets;
    }

    @Override
    public Ticket get(String ticketId) {
        OTRSTicketGet req = new OTRSTicketGet();
        req.setUserLogin(otrsUser);
        req.setPassword(otrsPassword);
        req.setTicketID(new BigInteger(ticketId));

        OTRSTicketGetResponse response = port.ticketGet(req);
        if (response.getTicket().isEmpty()) {
            return null;
        }
        return toTicket(response.getTicket().get(0));
    }

    @Override
    public String savaORUpdate(Ticket ticket) {
        if (ticket.getId() == null || ticket.getId().isBlank()) {
            return create(ticket);
        }
        return update(ticket);
    }

    private String create(Ticket ticket) {
        OTRSTicketCreateTicket t = new OTRSTicketCreateTicket();
        t.setTitle(ticket.getSummary());
        t.setQueue("Raw");
        t.setState("new");
        t.setPriority("3 normal");
        t.setCustomerUser(otrsUser);

        OTRSArticle article = new OTRSArticle();
        article.setSubject(ticket.getSummary());
        article.setBody(ticket.getDetails() != null ? ticket.getDetails() : "");
        article.setContentType("text/plain; charset=UTF-8");

        OTRSTicketCreate req = new OTRSTicketCreate();
        req.setUserLogin(otrsUser);
        req.setPassword(otrsPassword);
        req.setTicket(t);
        req.setArticle(article);

        OTRSTicketCreateResponse response = port.ticketCreate(req);
        return response.getTicketID().toString();
    }

    private String update(Ticket ticket) {
        OTRSTicketUpdateTicket t = new OTRSTicketUpdateTicket();
        t.setTitle(ticket.getSummary());

        OTRSTicketUpdate req = new OTRSTicketUpdate();
        req.setUserLogin(otrsUser);
        req.setPassword(otrsPassword);
        req.setTicketID(new BigInteger(ticket.getId()));
        req.setTicket(t);

        OTRSTicketUpdateResponse response = port.ticketUpdate(req);
        return response.getTicketID().toString();
    }

    private Ticket toTicket(OTRSTicketGetResponseTicket raw) {
        Ticket.State state = raw.getState() != null && raw.getState().contains("closed")
                ? Ticket.State.CLOSED
                : Ticket.State.OPEN;
        return ImmutableTicket.newBuilder()
                .setId(raw.getTicketID().toString())
                .setSummary(raw.getTitle())
                .setDetails(raw.getState())
                .setState(state)
                .build();
    }
}
