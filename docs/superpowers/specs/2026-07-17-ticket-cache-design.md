# Ticket Cache Design

## Problem

`Ticketer.get(ticketId)` and `Otrs6Client.get`/`getAll` do a live SOAP round-trip
to OTRS on every call. Repeated lookups for the same ticket (e.g. from
OpenNMS re-rendering the ticketing UI/API) pay that latency every time, even
when the underlying data hasn't changed. We want an in-memory cache with a
timeout so recently-fetched tickets can be served without hitting OTRS again,
while still expiring so data doesn't go stale forever.

## Goals

- Reduce latency for repeated `get(ticketId)` and `getAll()` calls within a
  short window.
- Entries expire automatically after a fixed timeout.
- Writes (`savaORUpdate`) never return stale data afterward.
- No new runtime dependency (this project already carries real OSGi
  packaging risk around JAX-WS/CXF wiring — see `CLAUDE.md`; avoid adding
  another bundle to reason about).

## Non-goals

- Configurable/tunable timeout (fixed constant for now).
- Persisting the cache across `ClientManager` rebuilds — a new
  `Otrs6Client`/`CachingOtrsClient` pair is created whenever credentials
  change, which naturally resets the cache.
- Caching negative lookups (`get()` returning `null` for a nonexistent
  ticket ID).

## Architecture

A new decorator, `CachingOtrsClient`, implements `OtrsClient` and wraps
another `OtrsClient` instance (the real `Otrs6Client`). It lives in
`it.arsinfo.opennms.plugins.otrs6.clients` alongside the interface it
implements.

`ClientManager.getOtrsClient()` wraps the client it builds:

```java
this.client = new CachingOtrsClient(new Otrs6Client(credentials));
```

`Ticketer` and `Otrs6Client` are unchanged — all caching happens behind the
`OtrsClient` interface `Ticketer` already depends on.

## Cache contents & expiry

Two independent cache slots, each entry expiring 5 minutes after being
written (expire-after-write, checked lazily on read — no background eviction
thread):

- `Map<String, Entry<Ticket>> ticketCache` — keyed by ticket ID, populated by
  `get(ticketId)`.
- `Entry<List<Ticket>> allCache` — single slot (no key), populated by
  `getAll()`.

A private static inner class `Entry<T>` holds the cached value plus the
`Instant` it expires at. Read path for both `get` and `getAll`:

1. Look up the entry.
2. If present and `clock.instant()` is before its expiry, return the cached
   value — delegate is not called.
3. Otherwise (absent or expired), call the delegate, and on a non-null
   result store a fresh `Entry` with `expiresAt = clock.instant().plus(timeout)`.

`get(ticketId)` returning `null` (ticket not found) is never cached — the
entry is only written when the delegate returns a non-null `Ticket`. This
keeps a not-yet-existing ticket from being stuck as a permanent cache miss
once it's actually created in OTRS.

`getAll()` and `get(ticketId)` do not cross-populate each other's cache —
fetching the full list does not seed individual `ticketCache` entries, and
vice versa. This keeps the two caches independent and simple to reason
about.

## Write invalidation

`savaORUpdate(ticket)` calls the delegate first (unchanged create/update
behavior), then:

1. Removes the `ticketCache` entry for the affected ticket ID (the ID
   returned by the delegate call, which covers both the create path — new
   ID — and the update path — `ticket.getId()`).
2. Clears `allCache` entirely, since the list result is now known-stale for
   that ticket.

The next `get`/`getAll` call after a write always goes to OTRS.

## Testability

Sleeping 5 minutes in a unit test is a non-starter. The constructor used by
`ClientManager`:

```java
public CachingOtrsClient(OtrsClient delegate)
```

defaults to `Clock.systemUTC()` and a 5-minute `Duration` timeout. A
package-private constructor takes an explicit `Clock` and `Duration`:

```java
CachingOtrsClient(OtrsClient delegate, Clock clock, Duration timeout)
```

— the same pattern `Otrs6Client` already uses for its test-only constructor
(`Otrs6Client(GenericTicketConnectorInterface port, String otrsUser, String otrsPassword)`).
Tests use a fixed/advanceable clock (e.g. `Clock.fixed(...)` swapped between
calls, or a small `Duration` with a mutable clock stub) to prove expiry
without real waiting.

## Testing plan

Unit tests (`CachingOtrsClientTest`) against a Mockito-mocked `OtrsClient`
delegate:

1. Repeated `get(id)` within the timeout hits the delegate exactly once;
   both calls return the same `Ticket`.
2. After the clock advances past the timeout, `get(id)` hits the delegate
   again.
3. `get(id)` returning `null` from the delegate is not cached — a second
   call still hits the delegate.
4. `saveOrUpdate` invalidates that ticket's `ticketCache` entry — a `get`
   for that ID immediately after a write hits the delegate again, not the
   stale cached value.
5. `saveOrUpdate` clears `allCache` — a `getAll()` call immediately after a
   write hits the delegate again.
6. `getAll()` is cached the same way as `get`: repeated calls within the
   timeout hit the delegate once; after timeout expiry, it hits again.

## Files touched

- New: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/clients/CachingOtrsClient.java`
- New: `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/clients/CachingOtrsClientTest.java`
- Modified: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/clients/ClientManager.java`
  (wrap the constructed `Otrs6Client` in `CachingOtrsClient`)
