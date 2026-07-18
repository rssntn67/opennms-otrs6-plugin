# PluginScheduler Design

## Problem

`AlarmTicketUpdater` and `OtrsTicketDao` (see their own design docs) are both
finished, tested `Runnable`s wired into blueprint as plain beans, but
nothing invokes `run()` on either — they're dormant. We want a scheduler
that periodically runs both, on a single dedicated thread, and that reports
its own health through OpenNMS's `HealthCheck` mechanism so a stalled
scheduler is visible in OpenNMS's health-check UI/API rather than failing
silently.

Reference: `../opennms-service-now-plugin/plugin/src/main/java/org/opennms/plugins/servicenow/PluginScheduler.java`
implements this exact pattern (single-thread scheduler + `HealthCheck`) for
a sibling plugin — this design follows its shape, adapted to this plugin's
two tasks and existing conventions (constructor injection, `Duration`
instead of raw millis, package-private test constructor).

## Goals

- One dedicated thread (a single-thread `ScheduledExecutorService`) runs
  both `AlarmTicketUpdater.run()` and `OtrsTicketDao.run()`, each every 5
  minutes via `scheduleWithFixedDelay`, both firing once immediately on
  start.
- Managed lifecycle: `start()` creates the executor and schedules both
  tasks; `stop()` shuts it down cleanly. Wired via blueprint's
  `init-method`/`destroy-method` so Aries calls them automatically on
  bundle activate/deactivate.
- Implements `org.opennms.integration.api.v1.health.HealthCheck` so
  OpenNMS's health-check subsystem can observe whether the scheduler is
  still alive, and is published as an OSGi `<service>` for that interface
  (the only class in this plugin published this way besides `Ticketer`'s
  `TicketingPlugin` service).
- No new dependency: `ImmutableResponse`/`Status`/`Context`/`Response` all
  live in `org.opennms.integration.api:common`, which `plugin/pom.xml`
  already depends on.

## Non-goals

- Configurable interval (fixed 5-minute constant, same precedent as
  `CachingOtrsClient`'s fixed timeout) — not exposed via shell/vault.
- Changing `AlarmTicketUpdater`/`OtrsTicketDao` themselves — they're
  untouched; this only drives their existing `run()` methods.
- A shell command to trigger a run manually or inspect scheduler state.

## Architecture

`PluginScheduler` (new, `it.arsinfo.opennms.plugins.otrs6.ticketing`,
alongside the two `Runnable`s it drives) implements `HealthCheck`. Its
public constructor takes the two tasks by name:

```java
public PluginScheduler(AlarmTicketUpdater alarmTicketUpdater, OtrsTicketDao otrsTicketDao)
```

A package-private constructor adds an explicit `Duration interval` for
tests:

```java
PluginScheduler(AlarmTicketUpdater alarmTicketUpdater, OtrsTicketDao otrsTicketDao, Duration interval)
```

## Lifecycle

```java
private static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(5);

private ScheduledExecutorService executor;
private ScheduledFuture<?> alarmTicketUpdaterFuture;
private ScheduledFuture<?> otrsTicketDaoFuture;

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
```

Rules, restated precisely:
- Exactly one thread total (`Executors.newSingleThreadScheduledExecutor`),
  named `otrs6-plugin-scheduler`, marked daemon so it can never block
  JVM/bundle shutdown by itself.
- `scheduleWithFixedDelay` for both tasks: each task's next run starts 5
  minutes after *that task's own* previous run finishes, not on a shared
  wall-clock tick. Since there's one thread, the two tasks never run
  concurrently — they interleave.
- `initialDelay = 0` for both — each runs once immediately on `start()`,
  then every 5 minutes thereafter.
- Neither task needs its own try/catch here: `AlarmTicketUpdater.run()` and
  `OtrsTicketDao.run()` already swallow every exception internally
  (fixed in earlier work specifically because an uncaught exception
  silently cancels future executions on a `ScheduledExecutorService`).
  `PluginScheduler` relies on that guarantee.
- `stop()` is idempotent and safe to call without a prior `start()`
  (`executor == null` guard), and safe to call twice.
- `stop()` shuts down gracefully (`shutdown()` + bounded
  `awaitTermination`), escalating to `shutdownNow()` if the graceful
  window (10s) or an interrupt occurs.

## HealthCheck integration

```java
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
```

Rules, restated precisely:
- Before `start()` has run (either future still `null`) → `Status.Starting`,
  message `"Not started"`. This is a deliberate improvement over the
  reference implementation, which reports `Success` in this state (its two
  futures being `null` short-circuits its `done` check to `false`) — here,
  "not started" is reported honestly rather than as "running."
- After `start()`, while both futures are still pending (the normal
  steady-state) → `Status.Success`, message `"Running"`.
- After `start()`, if either future is `isDone()` (cancelled, or its task's
  own reschedule chain died from something `run()`'s internal try/catch
  didn't cover, e.g. an `Error`) → `Status.Failure`, message `"Not
  running"`.
- `perform()` is not wrapped in its own try/catch — `HealthCheck.perform`
  is declared `throws Exception`, and `isDone()`/`ImmutableResponse`
  construction can't realistically throw, matching the reference
  implementation's approach.

## Blueprint wiring

```xml
<bean id="pluginScheduler" class="it.arsinfo.opennms.plugins.otrs6.ticketing.PluginScheduler"
      init-method="start" destroy-method="stop">
    <argument ref="alarmTicketUpdater"/>
    <argument ref="otrsTicketDao"/>
</bean>
<service interface="org.opennms.integration.api.v1.health.HealthCheck" ref="pluginScheduler"/>
```

Unlike `alarmTicketUpdater`/`otrsTicketDao` (plain beans, no `<service>`),
`pluginScheduler` **is** published as an OSGi `<service>` for the
`HealthCheck` interface — that's how OpenNMS's health-check subsystem
discovers it. No new `<reference>` is needed; both constructor arguments
are beans already defined in the file.

## Testing plan

New test file `PluginSchedulerTest`, using the package-private
`Duration`-injecting constructor with a short interval (e.g. 50ms) instead
of waiting 5 real minutes, matching `AlarmTicketUpdaterTest`/
`OtrsTicketDaoTest`'s conventions (JUnit 4, Mockito 2, mocked
`AlarmTicketUpdater`/`OtrsTicketDao`):

1. `start()` with a short test interval → both `alarmTicketUpdater.run()`
   and `otrsTicketDao.run()` are invoked at least twice within a bounded
   `Mockito.timeout(...)` wait (proves both the immediate first run and
   periodic rescheduling).
2. `stop()` after `start()` does not throw.
3. `stop()` before any `start()` does not throw (null-safe).
4. `stop()` called twice does not throw (idempotent).
5. `getDescription()` returns a non-blank string.
6. `perform()` before `start()` → `Status.Starting`.
7. `perform()` after `start()`, before either future completes →
   `Status.Success`.
8. `perform()` when a future is done (simulated via a interval short
   enough, and enough elapsed time, that `scheduleWithFixedDelay`'s
   returned future would still be pending in normal operation — instead,
   this is tested by cancelling the executor via `stop()` first, which
   makes the futures `isDone()`, then calling `perform()` and expecting
   `Status.Failure`) → `Status.Failure`.

## Files touched

- New: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/ticketing/PluginScheduler.java`
- New: `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/ticketing/PluginSchedulerTest.java`
- Modified: `plugin/src/main/resources/OSGI-INF/blueprint/blueprint.xml`
  (add `pluginScheduler` bean and its `HealthCheck` service publication)
