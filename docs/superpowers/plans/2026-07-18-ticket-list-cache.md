# TicketListCache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `GET /tickets` REST endpoint that returns the last-known OTRS ticket list, backed by a new `TicketListCache` holder that only `OtrsTicketDao`'s scheduled runs ever write to — so the endpoint can never trigger a live OTRS call.

**Architecture:** `TicketListCache` is a small, single-purpose holder (`volatile List<Ticket>` field, defaulting to empty) in the `ticketing` package. `OtrsTicketDao` gains it as a third constructor dependency and calls `ticketListCache.set(tickets)` right after each successful `client.getAll()`. `WebhookHandlerImpl` gains the same dependency and a new `tickets()` method that only ever calls `ticketListCache.get()` — no `ClientManager`/`ConnectionManager`/`OtrsClient` involved at all, so a live call is structurally impossible from this path.

**Tech Stack:** Java, JUnit 4, Hamcrest, Mockito 2, JAX-RS, OSGi Blueprint XML. No new dependency.

## Global Constraints

- No new compile/runtime dependency.
- `TicketListCache.get()` never returns `null`; defaults to `Collections.emptyList()` before the first `set()` call.
- `TicketListCache.set(tickets)` wraps the argument in `Collections.unmodifiableList` before storing.
- `TicketListCache`'s backing field is `volatile` (writer: `otrs6-plugin-scheduler` thread via `OtrsTicketDao`; readers: HTTP threads via `WebhookHandlerImpl`).
- `OtrsTicketDao`'s existing whole-method try/catch is unchanged in shape — `ticketListCache.set(...)` is called inside the same try block, after `client.getAll()`, so a failure anywhere before that point (client resolution, `getAll()` itself) means `set()` is simply never reached and the cache keeps its last successful value.
- `WebhookHandlerImpl.tickets()` never touches `ClientManager`, `ConnectionManager`, or `OtrsClient` — only `TicketListCache.get()`.
- New endpoint: `GET /tickets` (full path `/rest/opennms-otrs-6/tickets`), annotated `@Produces(MediaType.APPLICATION_JSON)`.
- Test style matches existing files in each touched package (JUnit 4, Hamcrest `assertThat`/`CoreMatchers`, Mockito 2 `mock`/`when`/`verify` where mocking is used).
- Spec: `docs/superpowers/specs/2026-07-18-ticket-list-cache-design.md`

---

### Task 1: `TicketListCache`

**Files:**
- Create: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/ticketing/TicketListCache.java`
- Create: `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/ticketing/TicketListCacheTest.java`

**Interfaces:**
- Consumes: `org.opennms.integration.api.v1.ticketing.Ticket` (existing).
- Produces: `public class TicketListCache` with `void set(List<Ticket> tickets)` and `List<Ticket> get()` — Tasks 2 and 3 both take this as a constructor dependency.

- [ ] **Step 1: Write the failing tests**

Create `TicketListCacheTest.java`:

```java
package it.arsinfo.opennms.plugins.otrs6.ticketing;

import org.junit.Before;
import org.junit.Test;
import org.opennms.integration.api.v1.ticketing.Ticket;
import org.opennms.integration.api.v1.ticketing.immutables.ImmutableTicket;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

public class TicketListCacheTest {

    private TicketListCache cache;

    @Before
    public void setUp() {
        cache = new TicketListCache();
    }

    @Test
    public void get_returnsEmptyListBeforeAnySet() {
        assertThat(cache.get(), equalTo(List.of()));
    }

    @Test
    public void get_returnsWhatWasSet() {
        Ticket ticket = ImmutableTicket.newBuilder().setId("1").setSummary("s").setState(Ticket.State.OPEN).build();

        cache.set(List.of(ticket));

        assertThat(cache.get(), equalTo(List.of(ticket)));
    }

    @Test
    public void get_returnsUnmodifiableList() {
        cache.set(new ArrayList<>());

        assertThrows(UnsupportedOperationException.class, () -> cache.get().add(
                ImmutableTicket.newBuilder().setId("1").setSummary("s").setState(Ticket.State.OPEN).build()));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=TicketListCacheTest -pl plugin`
Expected: FAIL to compile — `cannot find symbol: class TicketListCache` (the production class doesn't exist yet).

- [ ] **Step 3: Write the minimal implementation**

```java
package it.arsinfo.opennms.plugins.otrs6.ticketing;

import org.opennms.integration.api.v1.ticketing.Ticket;

import java.util.Collections;
import java.util.List;

public class TicketListCache {
    private volatile List<Ticket> tickets = Collections.emptyList();

    public void set(List<Ticket> tickets) {
        this.tickets = Collections.unmodifiableList(tickets);
    }

    public List<Ticket> get() {
        return tickets;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=TicketListCacheTest -pl plugin`
Expected: PASS — 3 tests run, 0 failures.

- [ ] **Step 5: Run the full test suite**

Run: `mvn test -pl plugin`
Expected: PASS — all 42 existing tests plus the 3 new ones pass.

- [ ] **Step 6: Commit**

```bash
git add plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/ticketing/TicketListCache.java plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/ticketing/TicketListCacheTest.java
git commit -m "feat: add TicketListCache holder for fast, cache-only ticket reads"
```

---

### Task 2: `OtrsTicketDao` publishes fetched tickets to `TicketListCache`

**Files:**
- Modify: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/ticketing/OtrsTicketDao.java`
- Modify: `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/ticketing/OtrsTicketDaoTest.java`

**Interfaces:**
- Consumes: `TicketListCache.set(List<Ticket>)` from Task 1.
- Produces: `OtrsTicketDao`'s constructor is now `OtrsTicketDao(ClientManager clientManager, ConnectionManager connectionManager, TicketListCache ticketListCache)` — Task 4's blueprint wiring needs the new argument.

- [ ] **Step 1: Update the test file**

Replace the full contents of `OtrsTicketDaoTest.java` with:

```java
package it.arsinfo.opennms.plugins.otrs6.ticketing;

import it.arsinfo.opennms.plugins.otrs6.clients.ClientManager;
import it.arsinfo.opennms.plugins.otrs6.clients.OtrsClient;
import it.arsinfo.opennms.plugins.otrs6.connection.ConnectionManager;
import org.junit.Before;
import org.junit.Test;
import org.opennms.integration.api.v1.ticketing.Ticket;
import org.opennms.integration.api.v1.ticketing.immutables.ImmutableTicket;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class OtrsTicketDaoTest {

    private ClientManager clientManager;
    private ConnectionManager connectionManager;
    private OtrsClient otrsClient;
    private TicketListCache ticketListCache;
    private OtrsTicketDao dao;

    @Before
    public void setUp() {
        clientManager = mock(ClientManager.class);
        connectionManager = mock(ConnectionManager.class);
        otrsClient = mock(OtrsClient.class);
        ticketListCache = mock(TicketListCache.class);
        dao = new OtrsTicketDao(clientManager, connectionManager, ticketListCache);

        when(clientManager.getOtrsClient(connectionManager)).thenReturn(Optional.of(otrsClient));
    }

    @Test
    public void run_skipsWhenNoConnectionConfigured() {
        when(clientManager.getOtrsClient(connectionManager)).thenReturn(Optional.empty());

        dao.run();

        verify(otrsClient, never()).getAll();
        verify(ticketListCache, never()).set(any());
    }

    @Test
    public void run_callsGetAllOnceWhenConnectionConfigured() {
        Ticket ticket = ImmutableTicket.newBuilder().setId("1").setSummary("s").setState(Ticket.State.OPEN).build();
        when(otrsClient.getAll()).thenReturn(List.of(ticket));

        dao.run();

        verify(otrsClient, times(1)).getAll();
    }

    @Test
    public void run_publishesFetchedTicketsToCache() {
        Ticket ticket = ImmutableTicket.newBuilder().setId("1").setSummary("s").setState(Ticket.State.OPEN).build();
        when(otrsClient.getAll()).thenReturn(List.of(ticket));

        dao.run();

        verify(ticketListCache, times(1)).set(List.of(ticket));
    }

    @Test
    public void run_doesNotPropagateExceptionFromGetAll() {
        when(otrsClient.getAll()).thenThrow(new RuntimeException("boom"));

        dao.run();

        verify(otrsClient, times(1)).getAll();
        verify(ticketListCache, never()).set(any());
    }

    @Test
    public void run_doesNotPropagateExceptionFromClientResolution() {
        when(clientManager.getOtrsClient(connectionManager)).thenThrow(new RuntimeException("vault unavailable"));

        dao.run();

        verify(otrsClient, never()).getAll();
        verify(ticketListCache, never()).set(any());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=OtrsTicketDaoTest -pl plugin`
Expected: FAIL to compile — `constructor OtrsTicketDao cannot be applied to given types` (the production constructor still only takes 2 arguments).

- [ ] **Step 3: Write the minimal implementation**

Replace the full contents of `OtrsTicketDao.java` with:

```java
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=OtrsTicketDaoTest -pl plugin`
Expected: PASS — 5 tests run, 0 failures.

- [ ] **Step 5: Run the full test suite**

Run: `mvn test -pl plugin`
Expected: PASS — all tests pass. `PluginSchedulerTest`, which constructs `OtrsTicketDao` only via `mock(OtrsTicketDao.class)`, is unaffected by the constructor change.

- [ ] **Step 6: Commit**

```bash
git add plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/ticketing/OtrsTicketDao.java plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/ticketing/OtrsTicketDaoTest.java
git commit -m "feat: publish fetched tickets to TicketListCache from OtrsTicketDao"
```

---

### Task 3: `WebhookHandler`/`WebhookHandlerImpl` gain `GET /tickets`

**Files:**
- Modify: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/rest/WebhookHandler.java`
- Modify: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/rest/WebhookHandlerImpl.java`
- Modify: `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/rest/WebhookHandlerImplTest.java`

**Interfaces:**
- Consumes: `TicketListCache.get(): List<Ticket>` from Task 1.
- Produces: `WebhookHandlerImpl`'s constructor is now `WebhookHandlerImpl(TicketListCache ticketListCache)` (previously no-arg) — Task 4's blueprint wiring needs the new argument.

- [ ] **Step 1: Update the test file**

Replace the full contents of `WebhookHandlerImplTest.java` with:

```java
package it.arsinfo.opennms.plugins.otrs6.rest;

import it.arsinfo.opennms.plugins.otrs6.ticketing.TicketListCache;
import org.junit.Before;
import org.junit.Test;
import org.opennms.integration.api.v1.ticketing.Ticket;
import org.opennms.integration.api.v1.ticketing.immutables.ImmutableTicket;

import javax.ws.rs.core.Response;
import java.util.List;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

public class WebhookHandlerImplTest {

    private TicketListCache ticketListCache;
    private WebhookHandlerImpl handler;

    @Before
    public void setUp() {
        ticketListCache = new TicketListCache();
        handler = new WebhookHandlerImpl(ticketListCache);
    }

    @Test
    public void ping_returnsOkStatus() {
        Response response = handler.ping();

        assertThat(response.getStatus(), equalTo(Response.Status.OK.getStatusCode()));
    }

    @Test
    public void ping_returnsPongEntity() {
        Response response = handler.ping();

        assertThat(response.getEntity(), equalTo("pong"));
    }

    @Test
    public void tickets_returnsEmptyListBeforeAnyDataIsCached() {
        Response response = handler.tickets();

        assertThat(response.getEntity(), equalTo(List.of()));
    }

    @Test
    public void tickets_returnsWhateverIsInTheCache() {
        Ticket ticket = ImmutableTicket.newBuilder().setId("1").setSummary("s").setState(Ticket.State.OPEN).build();
        ticketListCache.set(List.of(ticket));

        Response response = handler.tickets();

        assertThat(response.getEntity(), equalTo(List.of(ticket)));
    }
}
```

Note: this test uses a real `TicketListCache` instance rather than a mock — it's a trivial holder with no behavior beyond get/set (already independently tested in Task 1), so a real instance both simplifies the test and genuinely exercises the integration between `WebhookHandlerImpl` and `TicketListCache`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=WebhookHandlerImplTest -pl plugin`
Expected: FAIL to compile — `constructor WebhookHandlerImpl cannot be applied to given types` (still no-arg) and `cannot find symbol: method tickets()` (neither the interface method nor the impl exist yet).

- [ ] **Step 3: Write the minimal implementation**

Replace the full contents of `WebhookHandler.java` with:

```java
package it.arsinfo.opennms.plugins.otrs6.rest;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("opennms-otrs-6")
public interface WebhookHandler {

    @GET
    @Path("/ping")
    Response ping();

    @GET
    @Path("/tickets")
    @Produces(MediaType.APPLICATION_JSON)
    Response tickets();

}
```

Replace the full contents of `WebhookHandlerImpl.java` with:

```java
package it.arsinfo.opennms.plugins.otrs6.rest;

import it.arsinfo.opennms.plugins.otrs6.ticketing.TicketListCache;

import javax.ws.rs.core.Response;

public class WebhookHandlerImpl implements WebhookHandler {

    private final TicketListCache ticketListCache;

    public WebhookHandlerImpl(TicketListCache ticketListCache) {
        this.ticketListCache = ticketListCache;
    }

    @Override
    public Response ping() {
        return Response.ok("pong").build();
    }

    @Override
    public Response tickets() {
        return Response.ok(ticketListCache.get()).build();
    }

}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=WebhookHandlerImplTest -pl plugin`
Expected: PASS — 4 tests run, 0 failures.

- [ ] **Step 5: Run the full test suite**

Run: `mvn test -pl plugin`
Expected: PASS — all tests pass.

- [ ] **Step 6: Commit**

```bash
git add plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/rest/WebhookHandler.java plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/rest/WebhookHandlerImpl.java plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/rest/WebhookHandlerImplTest.java
git commit -m "feat: add GET /tickets endpoint reading from TicketListCache"
```

---

### Task 4: Wire `TicketListCache` into blueprint

**Files:**
- Modify: `plugin/src/main/resources/OSGI-INF/blueprint/blueprint.xml`

**Interfaces:**
- Consumes: `OtrsTicketDao(ClientManager, ConnectionManager, TicketListCache)` from Task 2; `WebhookHandlerImpl(TicketListCache)` from Task 3.
- Produces: a blueprint bean `ticketListCache`, not published as a service — it's a plain internal collaborator wired only into `otrsTicketDao` and `webhookHandlerImpl`.

**Why no new test here:** Blueprint XML isn't unit-tested anywhere in this project (Aries Blueprint validation only happens at OSGi deploy time). Verification is: the file stays well-formed XML following the existing bean patterns already in the file, plus a full build.

- [ ] **Step 1: Make the change**

In `blueprint.xml`, replace:

```xml
    <bean id="otrsTicketDao" class="it.arsinfo.opennms.plugins.otrs6.ticketing.OtrsTicketDao">
        <argument ref="clientManager"/>
        <argument ref="connectionManager"/>
    </bean>
```

with:

```xml
    <bean id="ticketListCache" class="it.arsinfo.opennms.plugins.otrs6.ticketing.TicketListCache"/>

    <bean id="otrsTicketDao" class="it.arsinfo.opennms.plugins.otrs6.ticketing.OtrsTicketDao">
        <argument ref="clientManager"/>
        <argument ref="connectionManager"/>
        <argument ref="ticketListCache"/>
    </bean>
```

Then replace:

```xml
    <bean id="webhookHandlerImpl" class="it.arsinfo.opennms.plugins.otrs6.rest.WebhookHandlerImpl"/>
```

with:

```xml
    <bean id="webhookHandlerImpl" class="it.arsinfo.opennms.plugins.otrs6.rest.WebhookHandlerImpl">
        <argument ref="ticketListCache"/>
    </bean>
```

- [ ] **Step 2: Run the full test suite**

Run: `mvn test -pl plugin`
Expected: PASS — all tests still pass; no compile errors.

- [ ] **Step 3: Run the full build**

Run: `mvn clean package` (full reactor, from repo root)
Expected: BUILD SUCCESS across all 5 reactor modules — confirms `blueprint.xml` is still well-formed and the bundle packages correctly.

- [ ] **Step 4: Commit**

```bash
git add plugin/src/main/resources/OSGI-INF/blueprint/blueprint.xml
git commit -m "feat: wire TicketListCache into blueprint"
```
