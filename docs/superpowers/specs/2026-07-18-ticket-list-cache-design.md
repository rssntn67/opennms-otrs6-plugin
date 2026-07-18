# TicketListCache + GET /tickets Endpoint Design

## Problem

`WebhookHandler`'s only endpoint today is `GET /ping`. We want a second
endpoint, `GET /tickets`, returning the full list of OTRS tickets — but it
must **never** trigger a live OTRS call itself, no matter what state the
existing `CachingOtrsClient` cache is in (cold, expired, or warm). It must
return instantly, always, from whatever was last fetched by the scheduled
`OtrsTicketDao` job — even if that's an empty list because the scheduler
hasn't run yet.

`CachingOtrsClient.getAll()` (see
`docs/superpowers/specs/2026-07-17-ticket-cache-design.md`) cannot be
called directly from this endpoint: on a cache miss or expiry, it performs
a live, blocking SOAP call to OTRS — exactly what an HTTP request thread
must never do here.

## Goals

- `GET /tickets` (`/rest/opennms-otrs-6/tickets`) returns the last-known
  ticket list as JSON, always instantly, with zero possibility of
  triggering a live OTRS call as a side effect of being requested.
- `OtrsTicketDao` (the existing scheduled cache-warmer, see
  `docs/superpowers/specs/2026-07-18-otrs-ticket-dao-design.md`) becomes
  the sole writer of this list, refreshing it once per scheduled run (every
  5 minutes, per `PluginScheduler`).
- Before the scheduler's first run, the endpoint returns an empty list
  rather than blocking or erroring.

## Non-goals

- Any fallback to a live OTRS call if the cached list is empty or stale —
  never, under any circumstance, from this endpoint.
- Guaranteeing JSON serialization ourselves (see Serialization below) —
  we're relying on the container, not bundling our own provider.
- Changing `CachingOtrsClient`'s own `getAll()`/`allCache` behavior at all
  — it's untouched. `OtrsTicketDao` still calls `client.getAll()` exactly
  as before; this is purely an additional side effect on top of that.

## Architecture

A new class, `TicketListCache`, in
`it.arsinfo.opennms.plugins.otrs6.ticketing` (alongside `OtrsTicketDao`,
the domain package for ticket-related state):

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

`volatile` gives cross-thread visibility: the writer is the
`otrs6-plugin-scheduler` thread (via `OtrsTicketDao`), the readers are HTTP
request threads (via `WebhookHandlerImpl`) — the same visibility need
`PluginScheduler`'s fields already have, solved the same way. `set()`
wraps the incoming list in `Collections.unmodifiableList` as a cheap
defensive measure against a caller mutating a list another thread might be
reading. Defaults to `Collections.emptyList()` so `get()` before the first
`set()` call returns an empty list, never `null`.

`OtrsTicketDao` gains a third constructor dependency and, after fetching,
publishes the result:

```java
public OtrsTicketDao(ClientManager clientManager, ConnectionManager connectionManager, TicketListCache ticketListCache)
```

```java
List<Ticket> tickets = client.get().getAll();
ticketListCache.set(tickets);
LOG.debug("Warmed OTRS ticket cache with {} tickets", tickets.size());
```

This doesn't change `OtrsTicketDao`'s own character — it still holds no
state itself and exposes no getter; the state lives in the separate,
single-purpose `TicketListCache` collaborator it writes to, the same way
it already writes to `LOG`. If `run()`'s existing whole-method try/catch
catches an exception (client resolution failure, `getAll()` failure), the
`set()` call is simply never reached, and the cache keeps whatever it had
from the last successful run — never cleared, never nulled out, by a
failed cycle.

`WebhookHandler`/`WebhookHandlerImpl` gain the same `TicketListCache`
dependency and a new endpoint:

```java
@GET
@Path("/tickets")
@Produces(MediaType.APPLICATION_JSON)
Response tickets();
```

```java
public WebhookHandlerImpl(TicketListCache ticketListCache) {
    this.ticketListCache = ticketListCache;
}

@Override
public Response tickets() {
    return Response.ok(ticketListCache.get()).build();
}
```

`ping()` is unchanged. The new `WebhookHandlerImpl` constructor takes
`TicketListCache` (replacing the previous no-arg constructor).

## Serialization

`Response.ok(tickets).build()` returns the domain objects (a
`List<Ticket>`, backed by `ImmutableTicket` instances) directly — the same
one-line pattern as the existing `ping()` method, no manual JSON handling.
This relies on OpenNMS's own JAX-RS whiteboard already having a JSON
`MessageBodyWriter` registered for the shared `/rest` application (highly
likely, since OpenNMS's own core REST API is JSON and shares that
whiteboard) — `@Produces(MediaType.APPLICATION_JSON)` makes the intended
content type explicit regardless. This assumption can only be confirmed by
an actual Karaf deploy and a real request to the endpoint, not by `mvn
test`; if serialization doesn't work as expected in a live environment, the
fallback would be adding an explicit Jackson JAX-RS provider dependency —
a separate follow-up, not part of this change.

## Blueprint wiring

```xml
<bean id="ticketListCache" class="it.arsinfo.opennms.plugins.otrs6.ticketing.TicketListCache"/>
```

`otrsTicketDao`'s bean gains a third `<argument>`:

```xml
<bean id="otrsTicketDao" class="it.arsinfo.opennms.plugins.otrs6.ticketing.OtrsTicketDao">
    <argument ref="clientManager"/>
    <argument ref="connectionManager"/>
    <argument ref="ticketListCache"/>
</bean>
```

`webhookHandlerImpl`'s bean's (previously empty) argument list gains one:

```xml
<bean id="webhookHandlerImpl" class="it.arsinfo.opennms.plugins.otrs6.rest.WebhookHandlerImpl">
    <argument ref="ticketListCache"/>
</bean>
```

## Testing plan

**`TicketListCacheTest`** (new):
1. `get()` before any `set()` call returns an empty list.
2. `set(tickets)` then `get()` returns the same tickets.
3. The list returned by `get()` is unmodifiable (attempting to mutate it
   throws `UnsupportedOperationException`).

**`OtrsTicketDaoTest`** (existing file, updated — constructor now takes a
third `TicketListCache` argument, mocked like `ClientManager`/
`ConnectionManager`):
4. `run()` with a connection configured calls `ticketListCache.set(...)`
   with the tickets returned by `client.getAll()`.
5. `run()` with no connection configured never calls
   `ticketListCache.set(...)`.
6. `run()` when `client.getAll()` throws never calls
   `ticketListCache.set(...)` (the existing whole-method try/catch already
   covers this — this test pins that `set()` specifically isn't reached).

**`WebhookHandlerImplTest`** (existing file, updated — constructor now
takes a `TicketListCache`, mocked):
7. `ping()`'s existing two tests still pass with the updated constructor
   (no behavior change).
8. `tickets()` returns whatever `ticketListCache.get()` returns, as the
   response entity.
9. `tickets()` with an empty `TicketListCache` (before any `set()`)
   returns an empty list, not an error.

## Files touched

- New: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/ticketing/TicketListCache.java`
- New: `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/ticketing/TicketListCacheTest.java`
- Modified: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/ticketing/OtrsTicketDao.java`
  (add `TicketListCache` constructor argument, publish fetched tickets)
- Modified: `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/ticketing/OtrsTicketDaoTest.java`
  (updated constructor calls, new assertions)
- Modified: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/rest/WebhookHandler.java`
  (add `tickets()` method)
- Modified: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/rest/WebhookHandlerImpl.java`
  (add `TicketListCache` constructor argument, implement `tickets()`)
- Modified: `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/rest/WebhookHandlerImplTest.java`
  (updated constructor calls, new tests)
- Modified: `plugin/src/main/resources/OSGI-INF/blueprint/blueprint.xml`
  (add `ticketListCache` bean, wire it into `otrsTicketDao` and
  `webhookHandlerImpl`)
