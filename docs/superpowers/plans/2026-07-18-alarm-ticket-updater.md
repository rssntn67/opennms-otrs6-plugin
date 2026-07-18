# AlarmTicketUpdater Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `AlarmTicketUpdater implements Runnable`, which for each alarm with an OTRS ticket checks the ticket's current state through the cache and calls `AlarmDao.setTicketState` when it has changed, plus a shared `ClientManager.getOtrsClient(ConnectionManager)` helper so `Ticketer` and `AlarmTicketUpdater` don't duplicate connection-resolution logic. Wire `AlarmDao` and the new class into blueprint.

**Architecture:** `ClientManager` gains an overload `getOtrsClient(ConnectionManager): Optional<OtrsClient>` that resolves the current `Connection` and looks up the (cached) client, returning `Optional.empty()` when unconfigured instead of throwing. `Ticketer.client()` is refactored onto it (unchanged external behavior). `AlarmTicketUpdater implements Runnable` (new, in the `ticketing` package) uses the same overload, then for each `alarmDao.getAlarms()` entry with a non-blank `ticketId`, fetches the ticket via the cache and calls `alarmDao.setTicketState(...)` on a state mismatch, catching and logging per-alarm failures so one bad alarm doesn't stop the rest.

**Tech Stack:** Java, JUnit 4, Mockito 2, Hamcrest, OSGi Blueprint XML. No new dependency.

## Global Constraints

- No new compile/runtime dependency.
- `ClientManager.getOtrsClient(ConnectionManager)` returns `Optional<OtrsClient>`; it never throws for a missing connection (unlike `Ticketer.client()`, which still throws — callers decide).
- `Ticketer`'s externally-observable behavior is unchanged: `get`/`saveOrUpdate` still throw `IllegalStateException("No OTRS connection configured")` when no connection is configured.
- `AlarmTicketUpdater.run()`: no connection configured → log a warning and return without calling `alarmDao.getAlarms()`. Alarm with `null`/blank `ticketId` → skip silently. `client.get(ticketId)` returns `null` → log a warning, skip that alarm. Ticket state unchanged → no `setTicketState` call. Ticket state changed → exactly one `alarmDao.setTicketState(ticket.getState(), alarm.getId())` call. Any exception while processing one alarm is caught, logged, and does not stop the remaining alarms in the same `run()`.
- `AlarmDao` blueprint reference is mandatory (no `availability="optional"` attribute).
- `AlarmTicketUpdater` is a plain blueprint `<bean>`, not published as an OSGi `<service>`.
- No scheduler/executor wiring in this plan — that's a separate future task.
- Test style matches `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/clients/Otrs6ClientTest.java`: JUnit 4 (`@Before`/`@Test`), Hamcrest `assertThat`/`CoreMatchers.*`, `org.mockito.ArgumentMatchers.*` + `org.mockito.Mockito.*` static imports. `Alarm` has no `Immutable*` builder in the OpenNMS integration API (unlike `Ticket`) — mock it directly with Mockito.
- **Constructing a real `Otrs6Client` does not work in a plain JVM test**, even pointed at a local WSDL file with no network access — verified empirically: it throws `javax.xml.ws.WebServiceException: Provider com.sun.xml.internal.ws.spi.ProviderImpl not found`, because no JAX-WS `Provider` implementation is on the `mvn test` classpath (that's only satisfied inside the OSGi/Karaf runtime — see `CLAUDE.md`'s "JAX-WS Runtime Wiring" section). Any test that needs a `ClientManager`/`ConnectionManager` collaborator MUST mock them (`mock(ClientManager.class)`, `mock(ConnectionManager.class)`) rather than constructing real ones with real credentials.
- Spec: `docs/superpowers/specs/2026-07-18-alarm-ticket-updater-design.md`

---

### Task 1: `ClientManager.getOtrsClient(ConnectionManager)` overload

**Files:**
- Modify: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/clients/ClientManager.java`
- Create: `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/clients/ClientManagerTest.java`

**Interfaces:**
- Consumes: `ConnectionManager.getConnection(): Optional<Connection>` (existing, `it.arsinfo.opennms.plugins.otrs6.connection.ConnectionManager`); `ClientManager.getOtrsClient(ClientCredentials): OtrsClient` and `ClientManager.asClientCredentials(Connection): ClientCredentials` (both existing, unchanged).
- Produces: `public Optional<OtrsClient> getOtrsClient(ConnectionManager connectionManager)` on `ClientManager` — Tasks 2 and 3 call this.

- [ ] **Step 1: Write the failing tests**

Create `ClientManagerTest.java`:

```java
package it.arsinfo.opennms.plugins.otrs6.clients;

import it.arsinfo.opennms.plugins.otrs6.connection.Connection;
import it.arsinfo.opennms.plugins.otrs6.connection.ConnectionManager;
import org.junit.Before;
import org.junit.Test;

import java.util.Optional;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.*;

public class ClientManagerTest {

    private ClientManager clientManager;
    private ConnectionManager connectionManager;

    @Before
    public void setUp() {
        clientManager = new ClientManager();
        connectionManager = mock(ConnectionManager.class);
    }

    @Test
    public void getOtrsClient_returnsEmptyWhenConnectionAbsent() {
        when(connectionManager.getConnection()).thenReturn(Optional.empty());

        Optional<OtrsClient> client = clientManager.getOtrsClient(connectionManager);

        assertThat(client, equalTo(Optional.empty()));
    }

    @Test
    public void asClientCredentials_mapsConnectionFieldsCorrectly() {
        Connection connection = mock(Connection.class);
        when(connection.getUrl()).thenReturn("http://otrs.example.com");
        when(connection.getUsername()).thenReturn("user");
        when(connection.getPassword()).thenReturn("pass");
        when(connection.isIgnoreSslCertificateValidation()).thenReturn(true);

        ClientCredentials credentials = ClientManager.asClientCredentials(connection);

        assertThat(credentials.url, equalTo("http://otrs.example.com"));
        assertThat(credentials.username, equalTo("user"));
        assertThat(credentials.password, equalTo("pass"));
        assertThat(credentials.ignoreSslCertificateValidation, equalTo(true));
    }
}
```

Note: there is deliberately no test for `getOtrsClient(ConnectionManager)`'s "connection present" branch constructing a real client — see this plan's Global Constraints on why that can't run in a plain JVM test. The `asClientCredentials_mapsConnectionFieldsCorrectly` test covers the credential-mapping half of that branch's logic without touching `Otrs6Client` construction.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=ClientManagerTest -pl plugin`
Expected: FAIL to compile — `cannot find symbol: method getOtrsClient(ConnectionManager)` (the overload doesn't exist yet).

- [ ] **Step 3: Write the minimal implementation**

In `ClientManager.java`, replace:

```java
import it.arsinfo.opennms.plugins.otrs6.connection.Connection;
import it.arsinfo.opennms.plugins.otrs6.connection.ConnectionValidationError;
```

with:

```java
import it.arsinfo.opennms.plugins.otrs6.connection.Connection;
import it.arsinfo.opennms.plugins.otrs6.connection.ConnectionManager;
import it.arsinfo.opennms.plugins.otrs6.connection.ConnectionValidationError;
```

Then replace:

```java
    public OtrsClient getOtrsClient(ClientCredentials credentials) {
        if (credentials.equals(this.credentials)) {
            return client;
        }
        this.credentials = credentials;
        this.client = new CachingOtrsClient(new Otrs6Client(credentials));
        return client;
    }

    public Optional<ConnectionValidationError> validate(Connection connection) {
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

    public Optional<OtrsClient> getOtrsClient(ConnectionManager connectionManager) {
        return connectionManager.getConnection()
                .map(ClientManager::asClientCredentials)
                .map(this::getOtrsClient);
    }

    public Optional<ConnectionValidationError> validate(Connection connection) {
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=ClientManagerTest -pl plugin`
Expected: PASS — 2 tests run, 0 failures.

- [ ] **Step 5: Run the full test suite**

Run: `mvn test -pl plugin`
Expected: PASS — all existing tests (`Otrs6ClientTest`, `CachingOtrsClientTest`, `ClientManagerTest`) still pass.

- [ ] **Step 6: Commit**

```bash
git add plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/clients/ClientManager.java plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/clients/ClientManagerTest.java
git commit -m "feat: add ClientManager.getOtrsClient(ConnectionManager) overload"
```

---

### Task 2: Refactor `Ticketer.client()` onto the shared overload

**Files:**
- Modify: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/ticketing/Ticketer.java`
- Create: `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/ticketing/TicketerTest.java`

**Interfaces:**
- Consumes: `ClientManager.getOtrsClient(ConnectionManager): Optional<OtrsClient>` from Task 1.
- Produces: `Ticketer`'s public behavior is unchanged (`get`/`saveOrUpdate` still throw `IllegalStateException("No OTRS connection configured")` when unconfigured) — no new interface for later tasks.

- [ ] **Step 1: Write the failing tests**

Create `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/ticketing/TicketerTest.java` (new directory — `ticketing` has no test package yet):

```java
package it.arsinfo.opennms.plugins.otrs6.ticketing;

import it.arsinfo.opennms.plugins.otrs6.clients.ClientManager;
import it.arsinfo.opennms.plugins.otrs6.clients.OtrsClient;
import it.arsinfo.opennms.plugins.otrs6.connection.ConnectionManager;
import org.junit.Before;
import org.junit.Test;
import org.opennms.integration.api.v1.ticketing.Ticket;
import org.opennms.integration.api.v1.ticketing.immutables.ImmutableTicket;

import java.util.Optional;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.*;

public class TicketerTest {

    private ClientManager clientManager;
    private ConnectionManager connectionManager;
    private OtrsClient otrsClient;
    private Ticketer ticketer;

    @Before
    public void setUp() {
        clientManager = mock(ClientManager.class);
        connectionManager = mock(ConnectionManager.class);
        otrsClient = mock(OtrsClient.class);
        ticketer = new Ticketer(clientManager, connectionManager);
    }

    @Test
    public void get_delegatesToResolvedClient() {
        when(clientManager.getOtrsClient(connectionManager)).thenReturn(Optional.of(otrsClient));
        Ticket ticket = ImmutableTicket.newBuilder().setId("1").setSummary("s").setState(Ticket.State.OPEN).build();
        when(otrsClient.get("1")).thenReturn(ticket);

        Ticket result = ticketer.get("1");

        assertThat(result, sameInstance(ticket));
    }

    @Test
    public void saveOrUpdate_delegatesToResolvedClient() {
        when(clientManager.getOtrsClient(connectionManager)).thenReturn(Optional.of(otrsClient));
        Ticket ticket = ImmutableTicket.newBuilder().setSummary("s").setState(Ticket.State.OPEN).build();
        when(otrsClient.savaORUpdate(ticket)).thenReturn("42");

        String id = ticketer.saveOrUpdate(ticket);

        assertThat(id, equalTo("42"));
    }

    @Test
    public void get_throwsWhenConnectionNotConfigured() {
        when(clientManager.getOtrsClient(connectionManager)).thenReturn(Optional.empty());

        try {
            ticketer.get("1");
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage(), equalTo("No OTRS connection configured"));
        }
    }

    @Test
    public void saveOrUpdate_throwsWhenConnectionNotConfigured() {
        when(clientManager.getOtrsClient(connectionManager)).thenReturn(Optional.empty());
        Ticket ticket = ImmutableTicket.newBuilder().setSummary("s").setState(Ticket.State.OPEN).build();

        try {
            ticketer.saveOrUpdate(ticket);
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage(), equalTo("No OTRS connection configured"));
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=TicketerTest -pl plugin`
Expected: 2 of the 4 tests FAIL. The current `client()` calls `connectionManager.getConnection()` directly (not the new `clientManager.getOtrsClient(connectionManager)` overload), so stubbing `clientManager.getOtrsClient(connectionManager)` has no effect yet — `connectionManager.getConnection()` is never stubbed and Mockito 2 defaults an unstubbed `Optional`-returning method to `Optional.empty()`, so `client()` always throws `IllegalStateException("No OTRS connection configured")` regardless of which test is running. That means:
- `get_delegatesToResolvedClient` and `saveOrUpdate_delegatesToResolvedClient` FAIL (error out with the unexpected exception) — this is the real RED signal for the behavior this task changes.
- `get_throwsWhenConnectionNotConfigured` and `saveOrUpdate_throwsWhenConnectionNotConfigured` already PASS even before the refactor — they characterize the unchanged "throws when unconfigured" behavior, which is already true today. That's expected, not a problem: only the two delegation tests need to go RED→GREEN.

- [ ] **Step 3: Write the minimal implementation**

In `Ticketer.java`, replace:

```java
    private OtrsClient client() {
        var connection = connectionManager.getConnection()
                .orElseThrow(() -> new IllegalStateException("No OTRS connection configured"));
        return clientManager.getOtrsClient(ClientManager.asClientCredentials(connection));
    }
```

with:

```java
    private OtrsClient client() {
        return clientManager.getOtrsClient(connectionManager)
                .orElseThrow(() -> new IllegalStateException("No OTRS connection configured"));
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=TicketerTest -pl plugin`
Expected: PASS — 4 tests run, 0 failures.

- [ ] **Step 5: Run the full test suite**

Run: `mvn test -pl plugin`
Expected: PASS — all tests across `clients` and `ticketing` packages still pass.

- [ ] **Step 6: Commit**

```bash
git add plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/ticketing/Ticketer.java plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/ticketing/TicketerTest.java
git commit -m "refactor: route Ticketer.client() through the shared ClientManager overload"
```

---

### Task 3: `AlarmTicketUpdater`

**Files:**
- Create: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/ticketing/AlarmTicketUpdater.java`
- Create: `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/ticketing/AlarmTicketUpdaterTest.java`

**Interfaces:**
- Consumes: `ClientManager.getOtrsClient(ConnectionManager): Optional<OtrsClient>` from Task 1; `OtrsClient.get(String): Ticket` (existing); `org.opennms.integration.api.v1.dao.AlarmDao` (existing, from the OpenNMS integration API): `List<Alarm> getAlarms()`, `void setTicketState(Ticket.State, int... alarmIds)`; `org.opennms.integration.api.v1.model.Alarm` (existing): `String getTicketId()`, `Ticket.State getTicketState()`, `Integer getId()`.
- Produces: `public class AlarmTicketUpdater implements Runnable` with constructor `AlarmTicketUpdater(ClientManager clientManager, ConnectionManager connectionManager, AlarmDao alarmDao)` — Task 4 wires this in blueprint.

- [ ] **Step 1: Write the failing tests**

Create `AlarmTicketUpdaterTest.java`:

```java
package it.arsinfo.opennms.plugins.otrs6.ticketing;

import it.arsinfo.opennms.plugins.otrs6.clients.ClientManager;
import it.arsinfo.opennms.plugins.otrs6.clients.OtrsClient;
import it.arsinfo.opennms.plugins.otrs6.connection.ConnectionManager;
import org.junit.Before;
import org.junit.Test;
import org.opennms.integration.api.v1.dao.AlarmDao;
import org.opennms.integration.api.v1.model.Alarm;
import org.opennms.integration.api.v1.ticketing.Ticket;
import org.opennms.integration.api.v1.ticketing.immutables.ImmutableTicket;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class AlarmTicketUpdaterTest {

    private ClientManager clientManager;
    private ConnectionManager connectionManager;
    private AlarmDao alarmDao;
    private OtrsClient otrsClient;
    private AlarmTicketUpdater updater;

    @Before
    public void setUp() {
        clientManager = mock(ClientManager.class);
        connectionManager = mock(ConnectionManager.class);
        alarmDao = mock(AlarmDao.class);
        otrsClient = mock(OtrsClient.class);
        updater = new AlarmTicketUpdater(clientManager, connectionManager, alarmDao);

        when(clientManager.getOtrsClient(connectionManager)).thenReturn(Optional.of(otrsClient));
    }

    @Test
    public void run_skipsWhenNoConnectionConfigured() {
        when(clientManager.getOtrsClient(connectionManager)).thenReturn(Optional.empty());

        updater.run();

        verify(alarmDao, never()).getAlarms();
    }

    @Test
    public void run_skipsAlarmWithNullTicketId() {
        Alarm alarm = mock(Alarm.class);
        when(alarm.getTicketId()).thenReturn(null);
        when(alarmDao.getAlarms()).thenReturn(List.of(alarm));

        updater.run();

        verify(otrsClient, never()).get(anyString());
    }

    @Test
    public void run_skipsAlarmWithBlankTicketId() {
        Alarm alarm = mock(Alarm.class);
        when(alarm.getTicketId()).thenReturn("");
        when(alarmDao.getAlarms()).thenReturn(List.of(alarm));

        updater.run();

        verify(otrsClient, never()).get(anyString());
    }

    @Test
    public void run_updatesAlarmWhenTicketStateChanged() {
        Alarm alarm = mock(Alarm.class);
        when(alarm.getId()).thenReturn(7);
        when(alarm.getTicketId()).thenReturn("1");
        when(alarm.getTicketState()).thenReturn(Ticket.State.OPEN);
        when(alarmDao.getAlarms()).thenReturn(List.of(alarm));

        Ticket ticket = ImmutableTicket.newBuilder().setId("1").setSummary("s").setState(Ticket.State.CLOSED).build();
        when(otrsClient.get("1")).thenReturn(ticket);

        updater.run();

        verify(alarmDao, times(1)).setTicketState(Ticket.State.CLOSED, 7);
    }

    @Test
    public void run_doesNotUpdateAlarmWhenTicketStateUnchanged() {
        Alarm alarm = mock(Alarm.class);
        when(alarm.getId()).thenReturn(7);
        when(alarm.getTicketId()).thenReturn("1");
        when(alarm.getTicketState()).thenReturn(Ticket.State.OPEN);
        when(alarmDao.getAlarms()).thenReturn(List.of(alarm));

        Ticket ticket = ImmutableTicket.newBuilder().setId("1").setSummary("s").setState(Ticket.State.OPEN).build();
        when(otrsClient.get("1")).thenReturn(ticket);

        updater.run();

        verify(alarmDao, never()).setTicketState(any(Ticket.State.class), anyInt());
    }

    @Test
    public void run_skipsAlarmWhenTicketNoLongerExists() {
        Alarm alarm = mock(Alarm.class);
        when(alarm.getId()).thenReturn(7);
        when(alarm.getTicketId()).thenReturn("1");
        when(alarmDao.getAlarms()).thenReturn(List.of(alarm));
        when(otrsClient.get("1")).thenReturn(null);

        updater.run();

        verify(alarmDao, never()).setTicketState(any(Ticket.State.class), anyInt());
    }

    @Test
    public void run_continuesProcessingAfterOneAlarmFails() {
        Alarm failing = mock(Alarm.class);
        when(failing.getId()).thenReturn(1);
        when(failing.getTicketId()).thenReturn("1");
        when(otrsClient.get("1")).thenThrow(new RuntimeException("boom"));

        Alarm healthy = mock(Alarm.class);
        when(healthy.getId()).thenReturn(2);
        when(healthy.getTicketId()).thenReturn("2");
        when(healthy.getTicketState()).thenReturn(Ticket.State.OPEN);
        Ticket ticket = ImmutableTicket.newBuilder().setId("2").setSummary("s").setState(Ticket.State.CLOSED).build();
        when(otrsClient.get("2")).thenReturn(ticket);

        when(alarmDao.getAlarms()).thenReturn(List.of(failing, healthy));

        updater.run();

        verify(alarmDao, times(1)).setTicketState(Ticket.State.CLOSED, 2);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=AlarmTicketUpdaterTest -pl plugin`
Expected: FAIL to compile — `cannot find symbol: class AlarmTicketUpdater` (the production class doesn't exist yet).

- [ ] **Step 3: Write the minimal implementation**

```java
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=AlarmTicketUpdaterTest -pl plugin`
Expected: PASS — 7 tests run, 0 failures.

- [ ] **Step 5: Run the full test suite**

Run: `mvn test -pl plugin`
Expected: PASS — all tests across `clients` and `ticketing` packages still pass.

- [ ] **Step 6: Commit**

```bash
git add plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/ticketing/AlarmTicketUpdater.java plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/ticketing/AlarmTicketUpdaterTest.java
git commit -m "feat: add AlarmTicketUpdater to sync alarm ticket state from OTRS"
```

---

### Task 4: Wire `AlarmDao` and `AlarmTicketUpdater` into blueprint

**Files:**
- Modify: `plugin/src/main/resources/OSGI-INF/blueprint/blueprint.xml`

**Interfaces:**
- Consumes: `AlarmTicketUpdater(ClientManager, ConnectionManager, AlarmDao)` from Task 3; existing blueprint bean ids `clientManager` and `connectionManager` (already defined in this file).
- Produces: a blueprint bean `alarmTicketUpdater`, not published as a service — nothing later in this plan consumes it (scheduling is out of scope).

**Why no new test here:** Blueprint XML isn't unit-tested anywhere in this project (there's no precedent for it, and Aries Blueprint validation only happens at OSGi deploy time, not at `mvn test`/`mvn package` time). Verification is: the file stays well-formed XML following the existing bean/reference patterns already in the file, plus a full build.

- [ ] **Step 1: Make the change**

In `blueprint.xml`, replace:

```xml
    <bean id="ticketer" class="it.arsinfo.opennms.plugins.otrs6.ticketing.Ticketer">
        <argument ref="clientManager"/>
        <argument ref="connectionManager"/>
    </bean>
    <service ref="ticketer" interface="org.opennms.integration.api.v1.ticketing.TicketingPlugin"/>

</blueprint>
```

with:

```xml
    <bean id="ticketer" class="it.arsinfo.opennms.plugins.otrs6.ticketing.Ticketer">
        <argument ref="clientManager"/>
        <argument ref="connectionManager"/>
    </bean>
    <service ref="ticketer" interface="org.opennms.integration.api.v1.ticketing.TicketingPlugin"/>

    <reference id="alarmDao" interface="org.opennms.integration.api.v1.dao.AlarmDao"/>

    <bean id="alarmTicketUpdater" class="it.arsinfo.opennms.plugins.otrs6.ticketing.AlarmTicketUpdater">
        <argument ref="clientManager"/>
        <argument ref="connectionManager"/>
        <argument ref="alarmDao"/>
    </bean>

</blueprint>
```

- [ ] **Step 2: Run the full test suite**

Run: `mvn test -pl plugin`
Expected: PASS — all tests (`Otrs6ClientTest`, `CachingOtrsClientTest`, `ClientManagerTest`, `TicketerTest`, `AlarmTicketUpdaterTest`) still pass; no compile errors.

- [ ] **Step 3: Run the full build**

Run: `mvn clean package -pl plugin -am`
Expected: BUILD SUCCESS — confirms `blueprint.xml` is still well-formed and the bundle packages correctly.

- [ ] **Step 4: Commit**

```bash
git add plugin/src/main/resources/OSGI-INF/blueprint/blueprint.xml
git commit -m "feat: wire AlarmDao and AlarmTicketUpdater into blueprint"
```
