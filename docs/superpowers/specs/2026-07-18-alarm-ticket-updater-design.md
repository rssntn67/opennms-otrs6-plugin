# AlarmTicketUpdater Design

## Problem

OpenNMS alarms can have an associated OTRS ticket (`Alarm.getTicketId()`) and
remember what state that ticket was in last time OpenNMS checked
(`Alarm.getTicketState()`). Nothing currently keeps that recorded state in
sync with OTRS — if a ticket is closed/reopened/updated in OTRS directly,
the alarm's `ticketState` goes stale. (A prior, incomplete attempt at this
existed as dead code — an unused `AlarmDao` field and a comment "add
scheduler to get all ticket state on OTRS and then update... for each alarm
with a ticket check opennms ticket status" — removed in commit `5077510`.)

We want a `Runnable` that, each time it runs, walks every alarm with a
ticket, checks the ticket's current state through the OTRS cache
(`CachingOtrsClient`, see `docs/superpowers/specs/2026-07-17-ticket-cache-design.md`),
and updates the alarm via `AlarmDao.setTicketState` when the state has
changed.

## Goals

- For each alarm with a non-blank `ticketId`, compare the ticket's current
  state (fetched via the cache) against the alarm's recorded ticket state,
  and call `AlarmDao.setTicketState` when they differ.
- Tolerate a missing OTRS connection and per-alarm failures without crashing
  the whole run.
- Share the existing `Connection` → `ClientCredentials` → `OtrsClient`
  resolution logic with `Ticketer` instead of duplicating it.
- Wire `AlarmDao` and the new class into blueprint.

## Non-goals

- Scheduling `AlarmTicketUpdater` to run periodically (no
  `ScheduledExecutorService`/timer wiring). This task only produces a
  `Runnable` and its blueprint bean; something will need to invoke `run()`
  later, but that's a separate task.
- A Karaf shell command to trigger a run manually.
- Publishing `AlarmTicketUpdater` as an OSGi `<service>` — nothing outside
  the blueprint container consumes it yet.
- Batching `setTicketState` calls across alarms that land on the same new
  state — one call per alarm is fine at this scale.

## Architecture

`AlarmTicketUpdater implements Runnable` lives in
`it.arsinfo.opennms.plugins.otrs6.ticketing`, next to `Ticketer`. Constructor:

```java
public AlarmTicketUpdater(ClientManager clientManager, ConnectionManager connectionManager, AlarmDao alarmDao)
```

## Shared client resolution

`Ticketer.client()` and the new class both need: resolve the current
`Connection` from `ConnectionManager`, convert it to `ClientCredentials`,
ask `ClientManager` for the (cached) `OtrsClient`. This moves into
`ClientManager` as a new overload, so there is one place that does it:

```java
public Optional<OtrsClient> getOtrsClient(ConnectionManager connectionManager) {
    return connectionManager.getConnection()
            .map(ClientManager::asClientCredentials)
            .map(this::getOtrsClient);
}
```

`Ticketer.client()` is refactored to use it, preserving its current
behavior exactly (throw when unconfigured):

```java
private OtrsClient client() {
    return clientManager.getOtrsClient(connectionManager)
            .orElseThrow(() -> new IllegalStateException("No OTRS connection configured"));
}
```

`AlarmTicketUpdater` calls the same overload but handles the empty case
differently (see below) instead of throwing.

## `run()` behavior

```java
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
```

Rules, restated precisely:
- No configured connection → log a warning and return; `alarmDao.getAlarms()`
  is never called.
- Alarm with a `null` or blank `ticketId` → skipped silently (not an error
  condition — most alarms have no ticket).
- `client.get(ticketId)` returns `null` (ticket no longer exists in OTRS) →
  log a warning, skip that alarm, continue with the rest.
- `ticket.getState() == alarm.getTicketState()` → nothing to do, no
  `setTicketState` call.
- `ticket.getState() != alarm.getTicketState()` → call
  `alarmDao.setTicketState(ticket.getState(), alarm.getId())`.
- Any exception while processing one alarm (cache/SOAP failure, etc.) is
  caught, logged, and does not stop the remaining alarms in the same
  `run()`.

## Blueprint wiring

`AlarmDao` is a mandatory reference (default availability — no
`availability="optional"` attribute) because `AlarmTicketUpdater`'s only
purpose depends on it; unlike `ConnectionManager`'s `RuntimeInfo`/
`SecureCredentialsVault` references, there's no meaningful degraded mode to
guard for. `AlarmTicketUpdater` is a plain blueprint `<bean>`, not published
as an OSGi `<service>`:

```xml
<reference id="alarmDao" interface="org.opennms.integration.api.v1.dao.AlarmDao"/>

<bean id="alarmTicketUpdater" class="it.arsinfo.opennms.plugins.otrs6.ticketing.AlarmTicketUpdater">
    <argument ref="clientManager"/>
    <argument ref="connectionManager"/>
    <argument ref="alarmDao"/>
</bean>
```

This adds to the existing `blueprint.xml`, alongside the current
`clientManager`, `connectionManager`, and `ticketer` beans — it does not
replace or restructure them.

## Testing plan

New test files, following the existing `Otrs6ClientTest`/
`CachingOtrsClientTest` conventions (JUnit 4, Hamcrest, Mockito 2, mocking
interfaces directly since `Alarm` has no `Immutable*` builder in the
OpenNMS integration API):

**`ClientManagerTest`** (new — `ClientManager` currently has no test file):
1. `getOtrsClient(ConnectionManager)` with a present `Connection` returns
   `Optional` containing the client built from that connection's
   credentials.
2. `getOtrsClient(ConnectionManager)` with an empty `Connection` returns
   `Optional.empty()`.

**`AlarmTicketUpdaterTest`** (new):
1. No connection configured → `run()` returns without calling
   `alarmDao.getAlarms()`.
2. Alarm with `null` `ticketId` → `client.get(...)` is never called for it.
3. Alarm with blank (`""`) `ticketId` → same as above.
4. Ticket state differs from the alarm's recorded state →
   `alarmDao.setTicketState(newState, alarmId)` is called exactly once with
   the ticket's state and the alarm's id.
5. Ticket state matches the alarm's recorded state → `setTicketState` is
   never called.
6. `client.get(ticketId)` returns `null` → no `setTicketState` call, no
   exception thrown out of `run()`.
7. One alarm's processing throws (e.g. `client.get` throws) → `run()` still
   processes the remaining alarms in the same call (verified via a second,
   well-behaved alarm in the same `getAlarms()` list whose `setTicketState`
   call is still observed).

**`TicketerTest`** (new — `Ticketer` currently has no test file; needed
because `Ticketer.client()` is being refactored to go through the new
shared `ClientManager` overload):
1. `get(ticketId)` delegates to the resolved client's `get`.
2. `saveOrUpdate(ticket)` delegates to the resolved client's
   `savaORUpdate`.
3. No connection configured → both `get` and `saveOrUpdate` throw
   `IllegalStateException("No OTRS connection configured")`, matching
   today's behavior.

## Files touched

- New: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/ticketing/AlarmTicketUpdater.java`
- New: `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/ticketing/AlarmTicketUpdaterTest.java`
- New: `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/ticketing/TicketerTest.java`
- New: `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/clients/ClientManagerTest.java`
- Modified: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/clients/ClientManager.java`
  (add `getOtrsClient(ConnectionManager)` overload)
- Modified: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/ticketing/Ticketer.java`
  (`client()` uses the new overload instead of duplicating resolution logic)
- Modified: `plugin/src/main/resources/OSGI-INF/blueprint/blueprint.xml`
  (add `alarmDao` reference and `alarmTicketUpdater` bean)
