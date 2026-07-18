# OtrsTicketDao Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `OtrsTicketDao implements Runnable`, which each time it runs calls the resolved `OtrsClient`'s `getAll()` purely to warm the (currently dormant in production) `CachingOtrsClient` list-cache slot, then wire it into blueprint.

**Architecture:** `OtrsTicketDao` (new, in the `ticketing` package) takes `ClientManager` and `ConnectionManager` in its constructor. `run()` resolves the client via the existing `ClientManager.getOtrsClient(ConnectionManager)` overload, calls `getAll()` on it (discarding the result — the point is the cache side effect), and wraps the whole method body in try/catch so nothing ever propagates out of `run()`, matching the fix already applied to `AlarmTicketUpdater.run()`.

**Tech Stack:** Java, JUnit 4, Mockito 2, OSGi Blueprint XML. No new dependency.

## Global Constraints

- No new compile/runtime dependency.
- `OtrsTicketDao` holds no state and exposes no getter/query method — it is a pure cache-warming side effect, not a read model, despite the "Dao" name.
- `run()`: no connection configured → log a warning and return; `getAll()` is never called. Connection present → call `client.getAll()` exactly once; log the fetched count at `DEBUG`. Any exception (from client resolution or from `getAll()` itself) is caught, logged at `ERROR`, and does not propagate out of `run()` — the whole method body is wrapped, not just part of it.
- `OtrsTicketDao` is a plain blueprint `<bean>`, not published as an OSGi `<service>`. No new OSGi `<reference>` is needed (unlike `AlarmTicketUpdater`'s `AlarmDao`), so this task carries no bundle-activation coupling risk.
- No scheduler/executor wiring in this plan — that's a separate future task.
- Test style matches `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/ticketing/AlarmTicketUpdaterTest.java`: JUnit 4 (`@Before`/`@Test`), `org.mockito.Mockito.*` static imports, mocked `ClientManager`/`ConnectionManager`/`OtrsClient` — never construct a real `Otrs6Client` (verified in an earlier plan: it throws `WebServiceException: Provider ... not found` in a plain JVM test).
- Spec: `docs/superpowers/specs/2026-07-18-otrs-ticket-dao-design.md`

---

### Task 1: `OtrsTicketDao`

**Files:**
- Create: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/ticketing/OtrsTicketDao.java`
- Create: `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/ticketing/OtrsTicketDaoTest.java`

**Interfaces:**
- Consumes: `ClientManager.getOtrsClient(ConnectionManager): Optional<OtrsClient>` (existing); `OtrsClient.getAll(): List<Ticket>` (existing).
- Produces: `public class OtrsTicketDao implements Runnable` with constructor `OtrsTicketDao(ClientManager clientManager, ConnectionManager connectionManager)` — Task 2 wires this in blueprint.

- [ ] **Step 1: Write the failing tests**

Create `OtrsTicketDaoTest.java`:

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

import static org.mockito.Mockito.*;

public class OtrsTicketDaoTest {

    private ClientManager clientManager;
    private ConnectionManager connectionManager;
    private OtrsClient otrsClient;
    private OtrsTicketDao dao;

    @Before
    public void setUp() {
        clientManager = mock(ClientManager.class);
        connectionManager = mock(ConnectionManager.class);
        otrsClient = mock(OtrsClient.class);
        dao = new OtrsTicketDao(clientManager, connectionManager);

        when(clientManager.getOtrsClient(connectionManager)).thenReturn(Optional.of(otrsClient));
    }

    @Test
    public void run_skipsWhenNoConnectionConfigured() {
        when(clientManager.getOtrsClient(connectionManager)).thenReturn(Optional.empty());

        dao.run();

        verify(otrsClient, never()).getAll();
    }

    @Test
    public void run_callsGetAllOnceWhenConnectionConfigured() {
        Ticket ticket = ImmutableTicket.newBuilder().setId("1").setSummary("s").setState(Ticket.State.OPEN).build();
        when(otrsClient.getAll()).thenReturn(List.of(ticket));

        dao.run();

        verify(otrsClient, times(1)).getAll();
    }

    @Test
    public void run_doesNotPropagateExceptionFromGetAll() {
        when(otrsClient.getAll()).thenThrow(new RuntimeException("boom"));

        dao.run();

        verify(otrsClient, times(1)).getAll();
    }

    @Test
    public void run_doesNotPropagateExceptionFromClientResolution() {
        when(clientManager.getOtrsClient(connectionManager)).thenThrow(new RuntimeException("vault unavailable"));

        dao.run();

        verify(otrsClient, never()).getAll();
    }
}
```

Note: the primary assertion in both exception tests is implicit — calling `dao.run()` without a surrounding try/catch means JUnit fails the test automatically if `run()` throws, so a clean pass already proves the exception was swallowed. The explicit `verify` calls add a second, independent check: `run_doesNotPropagateExceptionFromGetAll` confirms `getAll()` was actually reached (not skipped) before it threw, and `run_doesNotPropagateExceptionFromClientResolution` confirms `getAll()` was never reached at all, since resolution failed first.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=OtrsTicketDaoTest -pl plugin`
Expected: FAIL to compile — `cannot find symbol: class OtrsTicketDao` (the production class doesn't exist yet).

- [ ] **Step 3: Write the minimal implementation**

```java
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=OtrsTicketDaoTest -pl plugin`
Expected: PASS — 4 tests run, 0 failures.

- [ ] **Step 5: Run the full test suite**

Run: `mvn test -pl plugin`
Expected: PASS — all tests across `clients` and `ticketing` packages still pass.

- [ ] **Step 6: Commit**

```bash
git add plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/ticketing/OtrsTicketDao.java plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/ticketing/OtrsTicketDaoTest.java
git commit -m "feat: add OtrsTicketDao to warm the OTRS ticket list cache"
```

---

### Task 2: Wire `OtrsTicketDao` into blueprint

**Files:**
- Modify: `plugin/src/main/resources/OSGI-INF/blueprint/blueprint.xml`

**Interfaces:**
- Consumes: `OtrsTicketDao(ClientManager, ConnectionManager)` from Task 1; existing blueprint bean ids `clientManager` and `connectionManager`.
- Produces: a blueprint bean `otrsTicketDao`, not published as a service — nothing later consumes it (scheduling is out of scope).

**Why no new test here:** Blueprint XML isn't unit-tested anywhere in this project (Aries Blueprint validation only happens at OSGi deploy time). Verification is: the file stays well-formed XML following the existing bean patterns already in the file, plus a full build.

- [ ] **Step 1: Make the change**

In `blueprint.xml`, replace:

```xml
    <reference id="alarmDao" interface="org.opennms.integration.api.v1.dao.AlarmDao"/>

    <bean id="alarmTicketUpdater" class="it.arsinfo.opennms.plugins.otrs6.ticketing.AlarmTicketUpdater">
        <argument ref="clientManager"/>
        <argument ref="connectionManager"/>
        <argument ref="alarmDao"/>
    </bean>

</blueprint>
```

with:

```xml
    <reference id="alarmDao" interface="org.opennms.integration.api.v1.dao.AlarmDao"/>

    <bean id="alarmTicketUpdater" class="it.arsinfo.opennms.plugins.otrs6.ticketing.AlarmTicketUpdater">
        <argument ref="clientManager"/>
        <argument ref="connectionManager"/>
        <argument ref="alarmDao"/>
    </bean>

    <bean id="otrsTicketDao" class="it.arsinfo.opennms.plugins.otrs6.ticketing.OtrsTicketDao">
        <argument ref="clientManager"/>
        <argument ref="connectionManager"/>
    </bean>

</blueprint>
```

- [ ] **Step 2: Run the full test suite**

Run: `mvn test -pl plugin`
Expected: PASS — all tests (including the new `OtrsTicketDaoTest`) still pass; no compile errors.

- [ ] **Step 3: Run the full build**

Run: `mvn clean package -pl plugin -am`
Expected: BUILD SUCCESS — confirms `blueprint.xml` is still well-formed and the bundle packages correctly.

- [ ] **Step 4: Commit**

```bash
git add plugin/src/main/resources/OSGI-INF/blueprint/blueprint.xml
git commit -m "feat: wire OtrsTicketDao into blueprint"
```
