# OtrsTicketDao Design

## Problem

`CachingOtrsClient.getAll()` (see `docs/superpowers/specs/2026-07-17-ticket-cache-design.md`)
caches the full OTRS ticket list for 5 minutes, but nothing in the plugin
currently calls `getAll()` in production — `Ticketer` only implements
`get`/`saveOrUpdate`, and `AlarmTicketUpdater` only calls `get(ticketId)`
per alarm. That cache slot is dormant: the first real caller of `getAll()`
would always pay a live SOAP round-trip, defeating the point of caching it.

We want a `Runnable` that periodically calls `OtrsClient.getAll()` purely to
keep that cache slot warm, so that whenever something *does* need the full
ticket list, it's already cached.

## Goals

- `run()` calls the resolved client's `getAll()`, which — since the
  resolved client is always the `CachingOtrsClient` decorator — populates
  or refreshes the cache's `getAll()` slot as a side effect.
- Tolerate a missing OTRS connection and any exception without ever
  propagating out of `run()` (same reasoning as `AlarmTicketUpdater`: this
  is a `Runnable` destined for a scheduler, and an uncaught exception from
  a scheduled task can silently cancel future executions).
- Reuse the existing `ClientManager.getOtrsClient(ConnectionManager)`
  resolution helper — no new connection-resolution logic.
- Wire it into blueprint as a plain bean, matching `AlarmTicketUpdater`.

## Non-goals

- Storing or exposing the fetched ticket list. Despite the "Dao" name, this
  class holds no state and has no query/getter method — it is a pure
  cache-warming side effect, not a read model. (Flagged during design: the
  name suggests data access, but the explicit choice was "cache warmer
  only.")
- Scheduling `OtrsTicketDao` to run periodically (no
  `ScheduledExecutorService`/timer wiring) — same deferral as
  `AlarmTicketUpdater`; something will need to invoke `run()` later.
- A Karaf shell command to trigger a run manually.
- Publishing `OtrsTicketDao` as an OSGi `<service>`.

## Architecture

`OtrsTicketDao implements Runnable` lives in
`it.arsinfo.opennms.plugins.otrs6.ticketing`, alongside `Ticketer` and
`AlarmTicketUpdater`. Constructor:

```java
public OtrsTicketDao(ClientManager clientManager, ConnectionManager connectionManager)
```

No `AlarmDao` dependency — this class doesn't touch alarms.

## `run()` behavior

```java
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
```

Rules, restated precisely:
- No configured connection → log a warning and return; `getAll()` is never
  called.
- Connection present → call `client.getAll()` exactly once; log the count
  at `DEBUG` (this is a background warm-up, not an operator-facing event —
  `AlarmTicketUpdater` similarly logs nothing on its successful path).
- Any exception — from client resolution or from `getAll()` itself — is
  caught, logged at `ERROR`, and does not propagate out of `run()`. This
  matches the fix already applied to `AlarmTicketUpdater.run()`
  (`docs/superpowers/plans/2026-07-18-alarm-ticket-updater.md`'s final
  whole-branch review): the whole method body is wrapped, not just a
  per-item loop, because there is no per-item loop here.

## Blueprint wiring

`OtrsTicketDao` is a plain blueprint `<bean>`, not published as an OSGi
`<service>`, referencing the already-existing `clientManager` and
`connectionManager` beans. No new external OSGi `<reference>` is required
(unlike `AlarmTicketUpdater`'s mandatory `AlarmDao` reference), so this
addition carries no bundle-activation coupling risk:

```xml
<bean id="otrsTicketDao" class="it.arsinfo.opennms.plugins.otrs6.ticketing.OtrsTicketDao">
    <argument ref="clientManager"/>
    <argument ref="connectionManager"/>
</bean>
```

This adds to the existing `blueprint.xml` alongside the current beans — it
does not replace or restructure them.

## Testing plan

New test file `OtrsTicketDaoTest`, matching `AlarmTicketUpdaterTest`'s
conventions (JUnit 4, Mockito 2, mocked `ClientManager`/`ConnectionManager`/
`OtrsClient` — no real `Otrs6Client` construction):

1. No connection configured → `run()` returns without calling
   `otrsClient.getAll()`.
2. Connection present → `run()` calls `otrsClient.getAll()` exactly once.
3. `otrsClient.getAll()` throws → the exception is caught, does not
   propagate out of `run()`.
4. `clientManager.getOtrsClient(connectionManager)` itself throws → the
   exception is caught, does not propagate out of `run()`.

## Files touched

- New: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/ticketing/OtrsTicketDao.java`
- New: `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/ticketing/OtrsTicketDaoTest.java`
- Modified: `plugin/src/main/resources/OSGI-INF/blueprint/blueprint.xml`
  (add `otrsTicketDao` bean)
