# WebhookHandler Design

## Problem

The sibling plugin `../opennms-service-now-plugin` exposes a trivial JAX-RS
`GET /ping` endpoint (`WebhookHandler`/`WebhookHandlerImpl`), published as
an OSGi `<service>` with an `application-path` service property so
OpenNMS's own JAX-RS whiteboard auto-mounts it under `/rest`. This plugin
has no REST-reachable liveness check at all today (only the `HealthCheck`
service, which is polled internally by OpenNMS, not reachable directly over
HTTP). We want the same simple pattern reproduced here.

Despite the "Webhook" name, neither the reference implementation nor this
one is a real webhook receiver — it's a liveness ping, named to match the
reference project for consistency across the two plugins.

## Goals

- A `GET` endpoint reachable at `/rest/opennms-otrs-6/ping` returning HTTP
  200 with body `"pong"`.
- Published as an OSGi `<service>` so OpenNMS's JAX-RS whiteboard picks it
  up automatically — no manual servlet/JAX-RS application wiring.
- Wire the new `javax.ws.rs-api` runtime dependency the same careful way
  `CLAUDE.md`'s "JAX-WS Runtime Wiring" section documents for JAX-WS:
  explicit bundle in `karaf-features.xml`, not a Karaf feature that might
  drag in unwanted extras.

## Non-goals

- An actual webhook receiver (a `POST` endpoint accepting external
  callbacks) — the reference project doesn't have one either; both are
  ping-only.
- Any business logic behind the endpoint beyond the literal "pong" — no
  auth, no payload, no interaction with `ClientManager`/`Ticketer`.

## Architecture

New package `it.arsinfo.opennms.plugins.otrs6.rest`, alongside the
existing `clients`/`connection`/`ticketing`/`shell` packages, each owning
one concern:

```java
package it.arsinfo.opennms.plugins.otrs6.rest;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

@Path("opennms-otrs-6")
public interface WebhookHandler {
    @GET
    @Path("/ping")
    Response ping();
}
```

```java
package it.arsinfo.opennms.plugins.otrs6.rest;

import javax.ws.rs.core.Response;

public class WebhookHandlerImpl implements WebhookHandler {
    @Override
    public Response ping() {
        return Response.ok("pong").build();
    }
}
```

The reference implementation declares an unused `Logger` field (nothing in
`ping()` logs anything) — omitted here as dead code.

The `@Path("opennms-otrs-6")` value matches this plugin's existing Karaf
shell scope (`opennms-otrs-6`, see `it.arsinfo.opennms.plugins.otrs6.shell`),
so the endpoint lands at `/rest/opennms-otrs-6/ping`, keeping shell and REST
naming consistent within this plugin.

## New runtime dependency

`javax.ws.rs-api` is not currently on this bundle's classpath (only
JAX-WS-related specs are, per `CLAUDE.md`'s existing documentation). Two
changes, mirroring the reference project's validated, working setup for
the same `opennms.api.version` (1.6.1):

- `plugin/pom.xml`: add a `javax.ws.rs:javax.ws.rs-api` compile dependency,
  version `2.1.1` (via a `jaxrs.version` property in the root `pom.xml`,
  matching the existing `cxf.version`/`karaf.version` property style).
- `karaf-features/src/main/resources/features.xml`: add
  `<bundle dependency="true">mvn:javax.ws.rs/javax.ws.rs-api/2.1.1</bundle>`
  as a top-level runtime bundle in the `opennms-plugins-otrs6` feature —
  explicit, not pulled in via a Karaf feature, matching how the JAX-WS
  bundles are already handled here and how the reference project handles
  this exact dependency.

## Blueprint wiring

```xml
<bean id="webhookHandlerImpl" class="it.arsinfo.opennms.plugins.otrs6.rest.WebhookHandlerImpl"/>
<service interface="it.arsinfo.opennms.plugins.otrs6.rest.WebhookHandler" ref="webhookHandlerImpl">
    <service-properties>
        <entry key="application-path" value="/rest"/>
    </service-properties>
</service>
```

`webhookHandlerImpl` needs no constructor arguments and no other beans —
it's fully self-contained.

## Testing plan

New test file `WebhookHandlerImplTest` (a deviation from the reference
project, which has no test for this class — this project tests every
other class, so this one gets a test too):

1. `ping()` returns a `Response` with HTTP status 200.
2. `ping()`'s response entity is `"pong"`.

## Files touched

- New: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/rest/WebhookHandler.java`
- New: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/rest/WebhookHandlerImpl.java`
- New: `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/rest/WebhookHandlerImplTest.java`
- Modified: `pom.xml` (add `jaxrs.version` property and
  `javax.ws.rs:javax.ws.rs-api` to `dependencyManagement`)
- Modified: `plugin/pom.xml` (add the `javax.ws.rs-api` dependency)
- Modified: `karaf-features/src/main/resources/features.xml` (add the
  `javax.ws.rs-api` runtime bundle)
- Modified: `plugin/src/main/resources/OSGI-INF/blueprint/blueprint.xml`
  (add `webhookHandlerImpl` bean and its service publication)
