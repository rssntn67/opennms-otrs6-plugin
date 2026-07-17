package it.arsinfo.opennms.plugins.otrs6.clients;

import org.junit.Before;
import org.junit.Test;
import org.opennms.integration.api.v1.ticketing.Ticket;
import org.opennms.integration.api.v1.ticketing.immutables.ImmutableTicket;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.*;

public class CachingOtrsClientTest {

    private OtrsClient delegate;
    private MutableClock clock;
    private CachingOtrsClient cache;

    @Before
    public void setUp() {
        delegate = mock(OtrsClient.class);
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"));
        cache = new CachingOtrsClient(delegate, clock, Duration.ofMinutes(5));
    }

    @Test
    public void get_cachesWithinTimeout() {
        Ticket ticket = ImmutableTicket.newBuilder()
                .setId("1")
                .setSummary("Test ticket")
                .setState(Ticket.State.OPEN)
                .build();
        when(delegate.get("1")).thenReturn(ticket);

        Ticket first = cache.get("1");
        Ticket second = cache.get("1");

        assertThat(first, sameInstance(ticket));
        assertThat(second, sameInstance(ticket));
        verify(delegate, times(1)).get("1");
    }

    @Test
    public void get_refetchesAfterTimeoutExpires() {
        Ticket ticket = ImmutableTicket.newBuilder()
                .setId("1")
                .setSummary("Test ticket")
                .setState(Ticket.State.OPEN)
                .build();
        when(delegate.get("1")).thenReturn(ticket);

        cache.get("1");
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        cache.get("1");

        verify(delegate, times(2)).get("1");
    }

    @Test
    public void get_doesNotCacheNullResult() {
        when(delegate.get("99")).thenReturn(null);

        Ticket first = cache.get("99");
        Ticket second = cache.get("99");

        assertThat(first, nullValue());
        assertThat(second, nullValue());
        verify(delegate, times(2)).get("99");
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
