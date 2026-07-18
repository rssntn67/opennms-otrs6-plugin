# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Full build
mvn clean install

# Build without installing
mvn clean package

# Run unit tests only
mvn test

# Run a single unit test class
mvn test -Dtest=Otrs6ClientTest

# Run a single test method
mvn test -Dtest=Otrs6ClientTest#testMethodName
```

## Deploy to OpenNMS/Karaf

```bash
# Option 1: Karaf feature install
feature:repo-add mvn:it.arsinfo.opennms.plugins/karaf-features/0.1.0-SNAPSHOT/xml
feature:install opennms-plugins-otrs6

# Option 2: Copy KAR bundle directly
cp assembly/kar/target/opennms-otrs6-plugin.kar /opt/opennms/deploy/
```

## Architecture Overview

This is an **OpenNMS integration plugin** packaged as an OSGi bundle for
deployment in Apache Karaf (the runtime embedded in OpenNMS). The plugin
implements OpenNMS's `TicketingPlugin` API, backed by an OTRS6 instance's
`GenericTicketConnector` SOAP web service.

### Module Structure

- **`plugin/`** — Main OSGi bundle (`packaging: bundle`). All business logic lives here.
- **`karaf-features/`** — Defines the Karaf feature descriptor (`features.xml`) that declares bundle dependencies.
- **`assembly/kar/`** — Assembles the deployable KAR (Karaf Archive) artifact.

### Ticketing Pipeline

`Ticketer` (`it.arsinfo.opennms.plugins.otrs6.ticketing`) implements
OpenNMS's `TicketingPlugin` interface. On each call it resolves the current
`Connection` from `ConnectionManager`, converts it to `ClientCredentials`,
and asks `ClientManager` for an `OtrsClient` — so credential changes made at
runtime (via the shell commands below) take effect on the next ticket
operation without a restart. `ClientManager` caches a single `Otrs6Client`
instance and only rebuilds it when the credentials change (via
`ClientCredentials.equals`).

`Otrs6Client` (`it.arsinfo.opennms.plugins.otrs6.clients`) implements
`OtrsClient` against OTRS6's `GenericTicketConnector` SOAP endpoint, using
JAX-WS stub classes generated at build time by the `cxf-codegen-plugin` from
`plugin/src/main/wsdl/GenericTicketConnector.wsdl` into package
`it.arsinfo.opennms.plugins.otrs6.clients.otrs`.

`ClientManager` wraps the `Otrs6Client` it builds in `CachingOtrsClient`, a
decorator over `OtrsClient` that caches `get(ticketId)`/`getAll()` results
for a fixed 5-minute expire-after-write window (hand-rolled with
`ConcurrentHashMap` + an injectable `Clock`, no new dependency) and
invalidates both caches on `saveOrUpdate`. Design rationale:
`docs/superpowers/specs/2026-07-17-ticket-cache-design.md`.

`AlarmTicketUpdater` (same package) implements `Runnable`: for each
OpenNMS alarm with an OTRS ticket, it fetches the ticket's current state
through the cache and calls `AlarmDao.setTicketState(...)` when it's
changed. Design rationale:
`docs/superpowers/specs/2026-07-18-alarm-ticket-updater-design.md`.

`OtrsTicketDao` (same package) is a second unscheduled `Runnable`: its
`run()` calls `OtrsClient.getAll()` — a cache warm-up for
`CachingOtrsClient`'s otherwise-dormant `getAll()` slot — and now also
publishes the result to `TicketListCache`, a separate holder consumed by
the REST endpoint below. It still holds no state of its own; the state
lives in the injected `TicketListCache` collaborator.
Design rationale:
`docs/superpowers/specs/2026-07-18-otrs-ticket-dao-design.md`.

`PluginScheduler` (same package) is what actually runs both of the above:
a single dedicated daemon thread (`otrs6-plugin-scheduler`) calls
`run()` on each via `scheduleWithFixedDelay`, every 5 minutes, both firing
once immediately on startup. It also implements OpenNMS's `HealthCheck`
(the only other OSGi `<service>` publication in this plugin besides
`Ticketer`'s `TicketingPlugin`), reporting `Starting`/`Success`/`Failure`
based on whether either task's periodic reschedule chain is still alive.
Wired via blueprint's `init-method="start" destroy-method="stop"`. Design
rationale: `docs/superpowers/specs/2026-07-18-plugin-scheduler-design.md`.

### Connection Storage (Secure Credentials Vault)

There is no `.cfg` file for OTRS credentials. `ConnectionManager`
(`it.arsinfo.opennms.plugins.otrs6.connection`) persists the single, fixed-alias
(`"Default"`) OTRS connection in OpenNMS's `SecureCredentialsVault` (SCV) under
the key `otrs_6_connection_Default`, with `url` and
`ignoreSslCertificateValidation` stored as credential attributes alongside the
vaulted username/password. `ConnectionManager.ensureCore()` refuses to operate
unless `RuntimeInfo.getContainer() == Container.OPENNMS`.

Because there's no config file to hot-reload, credentials are managed
imperatively via Karaf shell commands (`it.arsinfo.opennms.plugins.otrs6.shell`,
scope `opennms-otrs-6`):

- `opennms-otrs-6:connection-add <url> <username> <password>` — creates the
  connection; validates against the live OTRS endpoint before saving unless
  `-f`/`--force` is passed, or use `-t`/`--test` for a dry run that never
  saves. `-i`/`--ignore-ssl-certificate-validation` skips TLS verification.
- `opennms-otrs-6:connection-get` — prints the current connection (password
  redacted).
- `opennms-otrs-6:connection-validate` — re-validates the stored connection
  against OTRS.
- `opennms-otrs-6:connection-delete` — removes it from the vault.

Only one connection can exist at a time (`connection-add` fails if one is
already present; delete first to replace it).

### Dependency Injection & Configuration

Components are wired via OSGi Blueprint XML at
`plugin/src/main/resources/OSGI-INF/blueprint/blueprint.xml`, which wires
`ClientManager` and `ConnectionManager` (the latter taking optional
references to OpenNMS's `RuntimeInfo` and `SecureCredentialsVault` services)
into `Ticketer`, registered as the `TicketingPlugin` OSGi service.
It also wires `AlarmDao` (OpenNMS's own service, no optional wrapper) into
`AlarmTicketUpdater`. Unlike `RuntimeInfo`/`SecureCredentialsVault`, this
reference is intentionally mandatory — `AlarmDao` is core to OpenNMS and
always registered, but it means the *whole bundle's* activation (not just
`AlarmTicketUpdater`) now blocks if `AlarmDao` is ever unavailable.
`ClientManager` and `ConnectionManager` are *also* individually published as
OSGi services (`<service>` elements, not just Blueprint-local beans) — the
shell commands are `@Service`-annotated Karaf actions (not part of the
Blueprint XML) that get them via `@Reference`, which only resolves against
the OSGi service registry. Without those extra `<service>` entries, the
shell commands install but every command silently fails to register
(`CommandExtension` logs "Command registration delayed... Missing service"
and never recovers).

### REST Endpoint

`WebhookHandler`/`WebhookHandlerImpl` (`it.arsinfo.opennms.plugins.otrs6.rest`)
expose a JAX-RS `GET /ping` (`@Path("opennms-otrs-6")`) returning "pong" —
a liveness ping, not a real webhook receiver, reproducing a pattern from
the sibling `opennms-service-now-plugin`. It's published as an OSGi
`<service>` with a `service-properties` entry `application-path=/rest`,
which is what makes OpenNMS's own JAX-RS whiteboard auto-mount it at
`/rest/opennms-otrs-6/ping` — no manual servlet wiring. Design rationale:
`docs/superpowers/specs/2026-07-18-webhook-handler-design.md`.

`GET /tickets` (`/rest/opennms-otrs-6/tickets`) returns the last-known
OTRS ticket list from `TicketListCache`
(`it.arsinfo.opennms.plugins.otrs6.ticketing`) — a `volatile` holder
written only by `OtrsTicketDao`'s scheduled runs and read only by
`WebhookHandlerImpl`. This endpoint has **zero code path** to
`ClientManager`/`ConnectionManager`/`OtrsClient` (deliberately, by
design, not just by testing) — it can never trigger a live OTRS call, it
just returns whatever was last fetched (an empty list before
`PluginScheduler`'s first run). Because this sits on top of
`CachingOtrsClient`'s own 5-minute TTL, served data can lag live OTRS by
up to roughly two refresh intervals in the worst case. Design rationale:
`docs/superpowers/specs/2026-07-18-ticket-list-cache-design.md`.

### JAX-WS Runtime Wiring

The generated SOAP stubs need `javax.jws`, `javax.xml.ws`, and `javax.xml.bind`
at OSGi runtime, not just compile time. `karaf-features/src/main/resources/features.xml`
provides these by listing the CXF 3.6.8 (`${cxf.version}`) client-side JAX-WS bundle
closure directly as `<bundle dependency="true">` entries, rather than
depending on Karaf's own `cxf-jaxws` feature (from
`org.apache.cxf.karaf:apache-cxf`'s features.xml). That feature pulls in two
things a pure JAX-WS client doesn't need and that OpenNMS's own bundled
system repo doesn't carry:
- `cxf-core` conditionally installs `cxf-commands` (CXF's shell integration)
  whenever the `shell` feature is already active — which it always is — and
  that needs `org.apache.cxf.karaf:cxf-karaf-commands`, an artifact OpenNMS
  doesn't bundle.
- `cxf-http` depends on Karaf's `http` (pax-web) feature, which OpenNMS
  deliberately doesn't use (it has its own Felix HTTP whiteboard bridge
  instead); `cxf-rt-transports-http` itself doesn't import any pax-web/jetty
  package, so it isn't actually needed for outbound client calls.
Also note `plugin/pom.xml`'s `maven-bundle-plugin` config overrides the
`Import-Package` ranges for `javax.jws`/`javax.jws.soap`/`javax.xml.ws`: the
compile-time-only Oracle spec jars declare narrower package versions than
what's actually exported at runtime (`jakarta.jws-api` 2.1.0, servicemix
`jaxws-api` 2.3), so bnd's inferred ranges don't resolve without widening.

### JAX-RS Runtime Wiring

`javax.ws.rs-api` (2.1.1, property `jaxrs.version`) is `provided` scope in
`plugin/pom.xml` (compile-time only) and satisfied at OSGi runtime by an
explicit `<bundle dependency="true">` entry in `karaf-features.xml` —
same philosophy as the JAX-WS wiring above, but simpler: the *same*
`javax.ws.rs-api` artifact is used for both the compile-time `provided`
dependency and the runtime bundle, so there's no compile/runtime version
mismatch and no `Import-Package` override is needed (unlike JAX-WS, where
the compile-time Oracle spec jars and runtime servicemix/jakarta bundles
are different artifacts). A `jersey-common` test-scope dependency
(`jersey.version`) is also needed — `javax.ws.rs-api` only supplies
interfaces, so `javax.ws.rs.core.Response.ok(...).build()` needs a
`RuntimeDelegate` implementation to actually construct a `Response` in a
plain JUnit test; `jersey-common` provides that without pulling in
`jersey-client`'s unused HTTP machinery.

### Key Technologies

- **OpenNMS Integration API 1.6.1** — `TicketingPlugin`, `SecureCredentialsVault`, `RuntimeInfo`
- **OSGi / Apache Karaf 4.3.10** — Bundle lifecycle, service registry, shell commands
- **OSGi Blueprint (Aries)** — Declarative dependency injection via XML
- **Apache CXF `cxf-codegen-plugin`** (build-time) / CXF 3.6.8 runtime bundles (deploy-time) — Generates and executes the JAX-WS client stubs from `GenericTicketConnector.wsdl`
- **JUnit 4 + Mockito 2** — Unit tests

### Test Conventions

- `*Test.java` = unit tests (Surefire, runs on `mvn test`)
