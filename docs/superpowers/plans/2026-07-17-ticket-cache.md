# Ticket Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a decorator, `CachingOtrsClient`, that caches `Ticket` lookups (`get`/`getAll`) with a 5-minute expire-after-write timeout, invalidated on `saveOrUpdate`, and wire it into `ClientManager`.

**Architecture:** `CachingOtrsClient implements OtrsClient`, wrapping another `OtrsClient` (the real `Otrs6Client`). It holds two independent cache slots — a `Map<String, Entry<Ticket>>` for `get(ticketId)` and a single `Entry<List<Ticket>>` for `getAll()` — each entry timestamped with an expiry `Instant`. `ClientManager.getOtrsClient()` wraps the `Otrs6Client` it builds in a `CachingOtrsClient`. `Ticketer` and `Otrs6Client` are untouched.

**Tech Stack:** Java, JUnit 4, Mockito 2, Hamcrest (matching `Otrs6ClientTest`'s existing style). No new runtime dependency.

## Global Constraints

- No new compile/runtime dependency — hand-rolled cache using JDK-only classes (`java.time.*`, `java.util.concurrent.ConcurrentHashMap`).
- Fixed timeout constant: 5 minutes. Not configurable via shell/vault.
- Cache is expire-after-write, checked lazily on read — no background eviction thread.
- `get(ticketId)` never caches a `null` (not-found) result.
- The per-ticket cache (`get`) and the list cache (`getAll`) are independent — neither cross-populates the other.
- `saveOrUpdate` invalidates the specific ticket's cache entry AND clears the entire `getAll` cache.
- Test style matches `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/clients/Otrs6ClientTest.java`: JUnit 4 (`@Before`/`@Test`), Hamcrest `assertThat`/`CoreMatchers`, Mockito `mock`/`when`/`verify`.
- Spec: `docs/superpowers/specs/2026-07-17-ticket-cache-design.md`

---

### Task 1: `CachingOtrsClient` — `get(ticketId)` caching with injectable clock

**Files:**
- Create: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/clients/CachingOtrsClient.java`
- Create: `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/clients/CachingOtrsClientTest.java`

**Interfaces:**
- Consumes: `OtrsClient` interface (`get(String)`, `getAll()`, `savaORUpdate(Ticket)`) from `it.arsinfo.opennms.plugins.otrs6.clients.OtrsClient`.
- Produces:
  - `public CachingOtrsClient(OtrsClient delegate)` — production constructor, `Clock.systemUTC()` + 5-minute timeout.
  - `CachingOtrsClient(OtrsClient delegate, Clock clock, Duration timeout)` — package-private test constructor.
  - Private static inner class `Entry<T>` with fields `value` (`T`) and `expiresAt` (`Instant`), and method `boolean isExpired(Clock clock)`.
  - `MutableClock` test helper (in the test file) with `advance(Duration)` to move time forward without sleeping.

- [ ] **Step 1: Write the failing test file**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=CachingOtrsClientTest -pl plugin`
Expected: FAIL to compile — `cannot find symbol: class CachingOtrsClient` (the production class doesn't exist yet).

- [ ] **Step 3: Write the minimal implementation**

```java
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
        return delegate.getAll();
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=CachingOtrsClientTest -pl plugin`
Expected: PASS — 3 tests run, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/clients/CachingOtrsClient.java plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/clients/CachingOtrsClientTest.java
git commit -m "feat: add CachingOtrsClient with expiring get(ticketId) cache"
```

---

### Task 2: `CachingOtrsClient` — cache `getAll()`

**Files:**
- Modify: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/clients/CachingOtrsClient.java`
- Modify: `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/clients/CachingOtrsClientTest.java`

**Interfaces:**
- Consumes: `Entry<T>` and the `clock`/`timeout` fields from Task 1.
- Produces: `getAll()` now returns cached results the same way `get` does; adds a `private volatile Entry<List<Ticket>> allCache` field for Task 3 to clear on write.

- [ ] **Step 1: Write the failing tests**

Add these two tests to `CachingOtrsClientTest`, right after `get_doesNotCacheNullResult` (before the `MutableClock` inner class):

```java
    @Test
    public void getAll_cachesWithinTimeout() {
        List<Ticket> tickets = List.of(
                ImmutableTicket.newBuilder().setId("1").setSummary("T1").setState(Ticket.State.OPEN).build(),
                ImmutableTicket.newBuilder().setId("2").setSummary("T2").setState(Ticket.State.CLOSED).build());
        when(delegate.getAll()).thenReturn(tickets);

        List<Ticket> first = cache.getAll();
        List<Ticket> second = cache.getAll();

        assertThat(first, sameInstance(tickets));
        assertThat(second, sameInstance(tickets));
        verify(delegate, times(1)).getAll();
    }

    @Test
    public void getAll_refetchesAfterTimeoutExpires() {
        List<Ticket> tickets = List.of(
                ImmutableTicket.newBuilder().setId("1").setSummary("T1").setState(Ticket.State.OPEN).build());
        when(delegate.getAll()).thenReturn(tickets);

        cache.getAll();
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        cache.getAll();

        verify(delegate, times(2)).getAll();
    }
```

Add this import alongside the existing ones at the top of `CachingOtrsClientTest.java` (the production class already imports `java.util.List` for its `getAll()` method signature):

```java
import java.util.List;
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=CachingOtrsClientTest -pl plugin`
Expected: FAIL — `getAll_cachesWithinTimeout` and `getAll_refetchesAfterTimeoutExpires` fail with `Wanted 1 time / but was 2 times` (or similar) on `verify(delegate, times(1)).getAll()`, because the current `getAll()` always delegates.

- [ ] **Step 3: Write the minimal implementation**

In `CachingOtrsClient.java`, replace:

```java
    private final Map<String, Entry<Ticket>> ticketCache = new ConcurrentHashMap<>();
```

with:

```java
    private final Map<String, Entry<Ticket>> ticketCache = new ConcurrentHashMap<>();
    private volatile Entry<List<Ticket>> allCache;
```

Then replace:

```java
    @Override
    public List<Ticket> getAll() {
        return delegate.getAll();
    }
```

with:

```java
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=CachingOtrsClientTest -pl plugin`
Expected: PASS — 5 tests run, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/clients/CachingOtrsClient.java plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/clients/CachingOtrsClientTest.java
git commit -m "feat: cache getAll() results in CachingOtrsClient"
```

---

### Task 3: `CachingOtrsClient` — invalidate on `saveOrUpdate`

**Files:**
- Modify: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/clients/CachingOtrsClient.java`
- Modify: `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/clients/CachingOtrsClientTest.java`

**Interfaces:**
- Consumes: `ticketCache`, `allCache` fields from Tasks 1–2.
- Produces: `savaORUpdate(Ticket)` now clears both caches; this is the last method changed in `CachingOtrsClient`, so the class is now feature-complete per the spec.

- [ ] **Step 1: Write the failing tests**

Add these two tests to `CachingOtrsClientTest`, right after `getAll_refetchesAfterTimeoutExpires` (before the `MutableClock` inner class):

```java
    @Test
    public void saveOrUpdate_invalidatesTicketCacheEntry() {
        Ticket original = ImmutableTicket.newBuilder()
                .setId("1").setSummary("Original").setState(Ticket.State.OPEN).build();
        when(delegate.get("1")).thenReturn(original);
        cache.get("1");

        Ticket updated = ImmutableTicket.newBuilder()
                .setId("1").setSummary("Updated").setState(Ticket.State.OPEN).build();
        when(delegate.savaORUpdate(updated)).thenReturn("1");

        cache.savaORUpdate(updated);
        cache.get("1");

        verify(delegate, times(2)).get("1");
    }

    @Test
    public void saveOrUpdate_clearsAllCache() {
        List<Ticket> tickets = List.of(
                ImmutableTicket.newBuilder().setId("1").setSummary("T1").setState(Ticket.State.OPEN).build());
        when(delegate.getAll()).thenReturn(tickets);
        cache.getAll();

        Ticket newTicket = ImmutableTicket.newBuilder()
                .setSummary("Brand new").setState(Ticket.State.OPEN).build();
        when(delegate.savaORUpdate(newTicket)).thenReturn("5");

        cache.savaORUpdate(newTicket);
        cache.getAll();

        verify(delegate, times(2)).getAll();
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=CachingOtrsClientTest -pl plugin`
Expected: FAIL — both new tests fail on their `verify(delegate, times(2))` assertions (actual: 1 time), because the current `savaORUpdate` doesn't touch either cache.

- [ ] **Step 3: Write the minimal implementation**

In `CachingOtrsClient.java`, replace:

```java
    @Override
    public String savaORUpdate(Ticket ticket) {
        return delegate.savaORUpdate(ticket);
    }
```

with:

```java
    @Override
    public String savaORUpdate(Ticket ticket) {
        String id = delegate.savaORUpdate(ticket);
        ticketCache.remove(id);
        allCache = null;
        return id;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=CachingOtrsClientTest -pl plugin`
Expected: PASS — 7 tests run, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/clients/CachingOtrsClient.java plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/clients/CachingOtrsClientTest.java
git commit -m "feat: invalidate CachingOtrsClient caches on saveOrUpdate"
```

---

### Task 4: Wire `CachingOtrsClient` into `ClientManager`

**Files:**
- Modify: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/clients/ClientManager.java:17-24`

**Interfaces:**
- Consumes: `public CachingOtrsClient(OtrsClient delegate)` from Task 1.
- Produces: `ClientManager.getOtrsClient(ClientCredentials)` now returns a `CachingOtrsClient`-wrapped client instead of a bare `Otrs6Client`. No other class depends on this return type change — `Ticketer.client()` already treats the result as `OtrsClient`.

**Why no new test here:** `ClientManager` has no existing unit tests, because `getOtrsClient`'s construction path (`new Otrs6Client(credentials)`) builds a real JAX-WS `GenericTicketConnector` using `credentials.url` as the WSDL location — it fetches/parses a WSDL document, which requires a live endpoint and isn't something a fast unit test should do (this is why `Otrs6ClientTest` uses the package-private mocked-port constructor instead of the public one). Wrapping the constructed client in `CachingOtrsClient` doesn't change that constraint or introduce a new gap — it's a one-line change validated by compilation plus the full existing test suite (`Otrs6ClientTest`, `CachingOtrsClientTest`) continuing to pass, and by `Ticketer`'s existing reliance on the `OtrsClient` interface (unaffected by the wrapping).

- [ ] **Step 1: Make the change**

In `ClientManager.java`, replace:

```java
    public OtrsClient getOtrsClient(ClientCredentials credentials) {
        if (credentials.equals(this.credentials)) {
            return client;
        }
        this.credentials = credentials;
        this.client = new Otrs6Client(credentials);
        return client;
    }
```

with:

```java
    public OtrsClient getOtrsClient(ClientCredentials credentials) {
        if (credentials.equals(this.credentials)) {
            return client;
        }
        this.credentials = credentials;
        this.client = new CachingOtrsClient(new Otrs6Client(credentials));
        return client;
    }
```

- [ ] **Step 2: Run the full test suite**

Run: `mvn test -pl plugin`
Expected: PASS — all existing tests (`Otrs6ClientTest`, `CachingOtrsClientTest`) still pass; no compile errors.

- [ ] **Step 3: Run the full build**

Run: `mvn clean package -pl plugin -am`
Expected: BUILD SUCCESS — confirms the bundle still packages correctly (no new OSGi import needed, since `CachingOtrsClient` only uses `java.time`, `java.util`, and `java.util.concurrent`, all part of the JDK baseline already available).

- [ ] **Step 4: Commit**

```bash
git add plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/clients/ClientManager.java
git commit -m "feat: wrap OTRS client in CachingOtrsClient via ClientManager"
```
