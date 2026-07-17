package it.arsinfo.opennms.plugins.otrs6.clients;

import org.opennms.integration.api.v1.ticketing.Ticket;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CachingOtrsClient implements OtrsClient {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final OtrsClient delegate;
    private final Clock clock;
    private final Duration timeout;

    private final Map<String, Entry<Ticket>> ticketCache = new ConcurrentHashMap<>();
    private volatile Entry<List<Ticket>> allCache;

    public CachingOtrsClient(OtrsClient delegate) {
        this(delegate, Clock.systemUTC(), DEFAULT_TIMEOUT);
    }

    CachingOtrsClient(OtrsClient delegate, Clock clock, Duration timeout) {
        this.delegate = delegate;
        this.clock = clock;
        this.timeout = timeout;
    }

    @Override
    public Ticket get(String ticketId) {
        Entry<Ticket> cached = ticketCache.get(ticketId);
        if (cached != null && !cached.isExpired(clock)) {
            return cached.value;
        }
        Ticket ticket = delegate.get(ticketId);
        if (ticket != null) {
            ticketCache.put(ticketId, new Entry<>(ticket, clock.instant().plus(timeout)));
        }
        return ticket;
    }

    @Override
    public List<Ticket> getAll() {
        Entry<List<Ticket>> cached = allCache;
        if (cached != null && !cached.isExpired(clock)) {
            return cached.value;
        }
        List<Ticket> tickets = delegate.getAll();
        allCache = new Entry<>(tickets, clock.instant().plus(timeout));
        return tickets;
    }

    @Override
    public String savaORUpdate(Ticket ticket) {
        return delegate.savaORUpdate(ticket);
    }

    private static final class Entry<T> {
        private final T value;
        private final Instant expiresAt;

        private Entry(T value, Instant expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }

        private boolean isExpired(Clock clock) {
            return !clock.instant().isBefore(expiresAt);
        }
    }
}
