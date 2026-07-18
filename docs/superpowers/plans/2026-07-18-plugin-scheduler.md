# PluginScheduler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `PluginScheduler`, a single-thread scheduler that runs `AlarmTicketUpdater` and `OtrsTicketDao` every 5 minutes and implements OpenNMS's `HealthCheck` interface, then wire it into blueprint.

**Architecture:** `PluginScheduler` owns a single-thread `ScheduledExecutorService`, created in `start()` and torn down in `stop()` (blueprint `init-method`/`destroy-method`). Both tasks are scheduled with `scheduleWithFixedDelay` (5-minute delay, 0 initial delay), and their `ScheduledFuture`s are tracked so `PluginScheduler.perform(Context)` (from `HealthCheck`) can report `Starting`/`Success`/`Failure` based on whether either task's periodic chain has died.

**Tech Stack:** Java, JUnit 4, Mockito 2, OSGi Blueprint XML, OpenNMS Integration API's `health` package (`org.opennms.integration.api:common`, already a dependency). No new dependency.

## Global Constraints

- No new compile/runtime dependency — `Context`/`Response`/`Status`/`HealthCheck` come from `org.opennms.integration.api.v1.health` (in the `api` artifact), `ImmutableResponse` from `org.opennms.integration.api.v1.health.immutables` (in the `common` artifact) — both already depended on by `plugin/pom.xml`.
- Exactly one thread total (`Executors.newSingleThreadScheduledExecutor`), named `otrs6-plugin-scheduler`, daemon.
- `scheduleWithFixedDelay` for both tasks, 5-minute fixed delay (`Duration.ofMinutes(5)` constant, not configurable), `initialDelay = 0` (both run once immediately on `start()`).
- `PluginScheduler` does not wrap either task's `run()` call in its own try/catch — both `AlarmTicketUpdater.run()` and `OtrsTicketDao.run()` already swallow every exception internally.
- `stop()` is idempotent and safe to call without a prior `start()`. It shuts down gracefully (`shutdown()` + bounded `awaitTermination(10, SECONDS)`), escalating to `shutdownNow()` on timeout or interrupt.
- `perform(Context)`: before `start()` (either future `null`) → `Status.Starting`, message `"Not started"`. After `start()`, while both futures are pending → `Status.Success`, message `"Running"`. After `start()`, if either future `isDone()` → `Status.Failure`, message `"Not running"`.
- `PluginScheduler` is published as an OSGi `<service>` for `HealthCheck` (unlike `AlarmTicketUpdater`/`OtrsTicketDao`, which are plain beans) — this is how OpenNMS's health-check subsystem discovers it.
- Test style matches `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/ticketing/AlarmTicketUpdaterTest.java`: JUnit 4, Mockito 2, mocked collaborators (`AlarmTicketUpdater`, `OtrsTicketDao` are concrete classes — mockable directly, same as `ClientManager`/`ConnectionManager` elsewhere in this codebase).
- Spec: `docs/superpowers/specs/2026-07-18-plugin-scheduler-design.md`

---

### Task 1: `PluginScheduler`

**Files:**
- Create: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/ticketing/PluginScheduler.java`
- Create: `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/ticketing/PluginSchedulerTest.java`

**Interfaces:**
- Consumes: `AlarmTicketUpdater.run()`, `OtrsTicketDao.run()` (both existing, `Runnable`); `org.opennms.integration.api.v1.health.HealthCheck` (`getDescription(): String`, `perform(Context): Response throws Exception`); `org.opennms.integration.api.v1.health.immutables.ImmutableResponse` (`newBuilder(): Builder`, `Builder.setStatus(Status)`, `Builder.setMessage(String)`, `Builder.build(): Response`).
- Produces: `public class PluginScheduler implements HealthCheck` with public constructor `PluginScheduler(AlarmTicketUpdater alarmTicketUpdater, OtrsTicketDao otrsTicketDao)` and public methods `start()`, `stop()` — Task 2 wires these into blueprint's `init-method`/`destroy-method`.

- [ ] **Step 1: Write the failing tests**

Create `PluginSchedulerTest.java`:

```java
package it.arsinfo.opennms.plugins.otrs6.ticketing;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.opennms.integration.api.v1.health.Context;
import org.opennms.integration.api.v1.health.Response;
import org.opennms.integration.api.v1.health.Status;

import java.time.Duration;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.*;

public class PluginSchedulerTest {

    private AlarmTicketUpdater alarmTicketUpdater;
    private OtrsTicketDao otrsTicketDao;
    private Context context;
    private PluginScheduler scheduler;

    @Before
    public void setUp() {
        alarmTicketUpdater = mock(AlarmTicketUpdater.class);
        otrsTicketDao = mock(OtrsTicketDao.class);
        context = mock(Context.class);
        scheduler = new PluginScheduler(alarmTicketUpdater, otrsTicketDao, Duration.ofMillis(50));
    }

    @After
    public void tearDown() {
        scheduler.stop();
    }

    @Test
    public void start_runsBothTasksRepeatedlyOnOneThread() {
        scheduler.start();

        verify(alarmTicketUpdater, timeout(1000).atLeast(2)).run();
        verify(otrsTicketDao, timeout(1000).atLeast(2)).run();
    }

    @Test
    public void stop_afterStartDoesNotThrow() {
        scheduler.start();

        scheduler.stop();
    }

    @Test
    public void stop_beforeStartDoesNotThrow() {
        scheduler.stop();
    }

    @Test
    public void stop_calledTwiceDoesNotThrow() {
        scheduler.start();

        scheduler.stop();
        scheduler.stop();
    }

    @Test
    public void getDescription_returnsNonBlankString() {
        String description = scheduler.getDescription();

        assertThat(description, notNullValue());
        assertThat(description.isBlank(), equalTo(false));
    }

    @Test
    public void perform_reportsStartingBeforeStart() {
        Response response = scheduler.perform(context);

        assertThat(response.getStatus(), equalTo(Status.Starting));
    }

    @Test
    public void perform_reportsSuccessWhileRunning() {
        scheduler.start();

        Response response = scheduler.perform(context);

        assertThat(response.getStatus(), equalTo(Status.Success));
    }

    @Test
    public void perform_reportsFailureWhenATaskFutureIsDone() {
        scheduler.start();
        scheduler.stop();

        Response response = scheduler.perform(context);

        assertThat(response.getStatus(), equalTo(Status.Failure));
    }
}
```

Note: `stop()` shuts down the executor, which under `ScheduledThreadPoolExecutor`'s default policy (`continueExistingPeriodicTasksAfterShutdownPolicy = false`) cancels pending periodic tasks — that's what drives their `ScheduledFuture`s to `isDone() == true`, which is what `perform_reportsFailureWhenATaskFutureIsDone` relies on.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=PluginSchedulerTest -pl plugin`
Expected: FAIL to compile — `cannot find symbol: class PluginScheduler` (the production class doesn't exist yet).

- [ ] **Step 3: Write the minimal implementation**

```java
package it.arsinfo.opennms.plugins.otrs6.ticketing;

import org.opennms.integration.api.v1.health.Context;
import org.opennms.integration.api.v1.health.HealthCheck;
import org.opennms.integration.api.v1.health.Response;
import org.opennms.integration.api.v1.health.Status;
import org.opennms.integration.api.v1.health.immutables.ImmutableResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class PluginScheduler implements HealthCheck {
    private static final Logger LOG = LoggerFactory.getLogger(PluginScheduler.class);
    private static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(5);

    private final AlarmTicketUpdater alarmTicketUpdater;
    private final OtrsTicketDao otrsTicketDao;
    private final Duration interval;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> alarmTicketUpdaterFuture;
    private ScheduledFuture<?> otrsTicketDaoFuture;

    public PluginScheduler(AlarmTicketUpdater alarmTicketUpdater, OtrsTicketDao otrsTicketDao) {
        this(alarmTicketUpdater, otrsTicketDao, DEFAULT_INTERVAL);
    }

    PluginScheduler(AlarmTicketUpdater alarmTicketUpdater, OtrsTicketDao otrsTicketDao, Duration interval) {
        this.alarmTicketUpdater = alarmTicketUpdater;
        this.otrsTicketDao = otrsTicketDao;
        this.interval = interval;
    }

    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "otrs6-plugin-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        long periodMillis = interval.toMillis();
        alarmTicketUpdaterFuture = executor.scheduleWithFixedDelay(alarmTicketUpdater, 0, periodMillis, TimeUnit.MILLISECONDS);
        otrsTicketDaoFuture = executor.scheduleWithFixedDelay(otrsTicketDao, 0, periodMillis, TimeUnit.MILLISECONDS);
        LOG.info("PluginScheduler started, running tasks every {}", interval);
    }

    public void stop() {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOG.info("PluginScheduler stopped");
    }

    @Override
    public String getDescription() {
        return "OTRS6 Plugin Scheduler";
    }

    @Override
    public Response perform(Context context) {
        if (alarmTicketUpdaterFuture == null || otrsTicketDaoFuture == null) {
            return ImmutableResponse.newBuilder()
                    .setStatus(Status.Starting)
                    .setMessage("Not started")
                    .build();
        }
        boolean done = alarmTicketUpdaterFuture.isDone() || otrsTicketDaoFuture.isDone();
        return ImmutableResponse.newBuilder()
                .setStatus(done ? Status.Failure : Status.Success)
                .setMessage(done ? "Not running" : "Running")
                .build();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=PluginSchedulerTest -pl plugin`
Expected: PASS — 8 tests run, 0 failures.

- [ ] **Step 5: Run the full test suite**

Run: `mvn test -pl plugin`
Expected: PASS — all tests across `clients` and `ticketing` packages still pass.

- [ ] **Step 6: Commit**

```bash
git add plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/ticketing/PluginScheduler.java plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/ticketing/PluginSchedulerTest.java
git commit -m "feat: add PluginScheduler to run AlarmTicketUpdater/OtrsTicketDao every 5 minutes"
```

---

### Task 2: Wire `PluginScheduler` into blueprint

**Files:**
- Modify: `plugin/src/main/resources/OSGI-INF/blueprint/blueprint.xml`

**Interfaces:**
- Consumes: `PluginScheduler(AlarmTicketUpdater, OtrsTicketDao)`, `start()`, `stop()` from Task 1; existing blueprint bean ids `alarmTicketUpdater` and `otrsTicketDao`.
- Produces: a blueprint bean `pluginScheduler`, published as an OSGi `<service>` for `org.opennms.integration.api.v1.health.HealthCheck` — this is a new pattern for this file, but matches how `ticketer` is published for `TicketingPlugin`.

**Why no new test here:** Blueprint XML isn't unit-tested anywhere in this project (Aries Blueprint validation only happens at OSGi deploy time). Verification is: the file stays well-formed XML following the existing bean/service patterns already in the file, plus a full build.

- [ ] **Step 1: Make the change**

In `blueprint.xml`, replace:

```xml
    <bean id="otrsTicketDao" class="it.arsinfo.opennms.plugins.otrs6.ticketing.OtrsTicketDao">
        <argument ref="clientManager"/>
        <argument ref="connectionManager"/>
    </bean>

</blueprint>
```

with:

```xml
    <bean id="otrsTicketDao" class="it.arsinfo.opennms.plugins.otrs6.ticketing.OtrsTicketDao">
        <argument ref="clientManager"/>
        <argument ref="connectionManager"/>
    </bean>

    <bean id="pluginScheduler" class="it.arsinfo.opennms.plugins.otrs6.ticketing.PluginScheduler"
          init-method="start" destroy-method="stop">
        <argument ref="alarmTicketUpdater"/>
        <argument ref="otrsTicketDao"/>
    </bean>
    <service interface="org.opennms.integration.api.v1.health.HealthCheck" ref="pluginScheduler"/>

</blueprint>
```

- [ ] **Step 2: Run the full test suite**

Run: `mvn test -pl plugin`
Expected: PASS — all tests (including the new `PluginSchedulerTest`) still pass; no compile errors.

- [ ] **Step 3: Run the full build**

Run: `mvn clean package -pl plugin -am`
Expected: BUILD SUCCESS — confirms `blueprint.xml` is still well-formed and the bundle packages correctly.

- [ ] **Step 4: Commit**

```bash
git add plugin/src/main/resources/OSGI-INF/blueprint/blueprint.xml
git commit -m "feat: wire PluginScheduler into blueprint as a HealthCheck service"
```
