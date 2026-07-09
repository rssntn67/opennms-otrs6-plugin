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
OpenNMS's `TicketingPlugin` interface and delegates to an `OtrsClient`
implementation. `Otrs6Client` (`it.arsinfo.opennms.plugins.otrs6.clients`)
implements `OtrsClient` against OTRS6's `GenericTicketConnector` SOAP
endpoint, using JAX-WS stub classes generated at build time by the
`cxf-codegen-plugin` from `plugin/src/main/wsdl/GenericTicketConnector.wsdl`
into package `it.arsinfo.opennms.plugins.otrs6.clients.otrs`.

### Dependency Injection & Configuration

Components are wired via OSGi Blueprint XML at
`plugin/src/main/resources/OSGI-INF/blueprint/blueprint.xml`.

Runtime configuration is read from
`$OPENNMS_HOME/etc/it.arsinfo.opennms.plugins.otrs6.cfg` with hot-reload support:
- `otrsUrl` — OTRS `GenericTicketConnector` SOAP endpoint (default: `http://127.0.0.1/otrs/nph-genericinterface.pl/Webservice/GenericTicketConnector`)
- `otrsUser` — OTRS user login (default: `root@localhost`)
- `otrsPassword` — OTRS user password (default: `root`)

### Key Technologies

- **OpenNMS Integration API 1.6.1** — `TicketingPlugin`, `AlarmDao`
- **OSGi / Apache Karaf 4.3.10** — Bundle lifecycle, service registry
- **OSGi Blueprint (Aries)** — Declarative dependency injection via XML
- **Apache CXF `cxf-codegen-plugin`** — Generates JAX-WS client stubs from `GenericTicketConnector.wsdl`
- **JUnit 4 + Mockito 2** — Unit tests

### Test Conventions

- `*Test.java` = unit tests (Surefire, runs on `mvn test`)

## Known Limitations

The `karaf-features` module's Karaf feature verification (`karaf-maven-plugin:verify`)
is currently skipped. The generated SOAP client stubs only need `javax.jws` /
`javax.xml.ws` at compile time (`provided` scope), but resolving a working
JAX-WS runtime inside Karaf at deploy time requires wiring in an OSGi JAX-WS
implementation (e.g. Karaf's `cxf`/`cxf-jaxws` feature), which isn't done yet.
Deploying this plugin to a live Karaf instance will need that runtime wiring
added first.
