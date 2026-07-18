# WebhookHandler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reproduce `opennms-service-now-plugin`'s `WebhookHandler`/`WebhookHandlerImpl` pattern — a JAX-RS `GET /ping` endpoint published as an OSGi service so OpenNMS's JAX-RS whiteboard auto-mounts it under `/rest` — in this plugin, reachable at `/rest/opennms-otrs-6/ping`.

**Architecture:** A new package `it.arsinfo.opennms.plugins.otrs6.rest` holds a `WebhookHandler` interface (`@Path("opennms-otrs-6")`, one `@GET @Path("/ping")` method) and `WebhookHandlerImpl` (returns `Response.ok("pong").build()`). This requires a new runtime dependency, `javax.ws.rs-api`, wired the same careful way `CLAUDE.md` documents for JAX-WS: `provided` scope in `plugin/pom.xml` (compile-time only, not embedded in the bundle) plus an explicit runtime bundle in `karaf-features.xml` (not a Karaf feature that might drag in extras).

**Tech Stack:** Java, JUnit 4, Hamcrest, JAX-RS (`javax.ws.rs-api` 2.1.1), OSGi Blueprint XML, Maven/Karaf feature descriptors.

## Global Constraints

- New runtime dependency: `javax.ws.rs:javax.ws.rs-api:2.1.1` — this project's first JAX-RS dependency. Follow this project's own established pattern for spec-only compile-time API jars (see the existing `javax.xml.bind:jaxb-api`/`javax.xml.ws:jaxws-api`/`javax.jws:javax.jws-api` entries in `plugin/pom.xml`): `<scope>provided</scope>` in `plugin/pom.xml` (available for compile/test, not embedded in the bundle or listed as a runtime dependency of the bundle itself), with the actual runtime implementation supplied by an explicit `<bundle dependency="true">` entry in `karaf-features.xml` — **not** the reference project's plain default-scope dependency, and **not** a dependency on some larger Karaf JAX-RS feature.
- New Maven property `jaxrs.version` = `2.1.1` in the root `pom.xml`, alongside the existing `cxf.version`/`karaf.version`/etc. properties, inserted alphabetically.
- `WebhookHandler`/`WebhookHandlerImpl` live in a new package, `it.arsinfo.opennms.plugins.otrs6.rest`, alongside the existing `clients`/`connection`/`ticketing`/`shell` packages.
- `WebhookHandlerImpl` has no constructor arguments and no `Logger` field (the reference implementation declares an unused one — omit it, it's dead code).
- `@Path("opennms-otrs-6")` on the interface (matches this plugin's existing Karaf shell scope name), `@Path("/ping")` on the method — final endpoint: `/rest/opennms-otrs-6/ping`.
- `WebhookHandlerImpl` is published as an OSGi `<service>` for the `WebhookHandler` interface with a `service-properties` entry `application-path` = `/rest` — this is what makes OpenNMS's JAX-RS whiteboard auto-mount it; it is not published for any other interface and needs no other services injected.
- Test style matches `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/clients/Otrs6ClientTest.java`: JUnit 4, Hamcrest `assertThat`/`CoreMatchers`.
- Spec: `docs/superpowers/specs/2026-07-18-webhook-handler-design.md`

---

### Task 1: Wire the `javax.ws.rs-api` runtime dependency

**Files:**
- Modify: `pom.xml`
- Modify: `plugin/pom.xml`
- Modify: `karaf-features/src/main/resources/features.xml`

**Interfaces:**
- Consumes: nothing.
- Produces: `javax.ws.rs.*` (JAX-RS annotations: `@GET`, `@Path`, and `javax.ws.rs.core.Response`) available at compile and test scope for Task 2's new classes, and satisfied at OSGi runtime by the new `karaf-features.xml` bundle entry.

**Why no new test here:** This task only changes build/dependency configuration — there is no Java code to unit test. Verification is: the module still compiles and tests still pass (nothing depends on the new dependency yet), and the full build's `karaf:verify` step (part of `assembly/kar`'s `mvn clean package`, which resolves every feature's bundle list against configured repositories) confirms the new bundle coordinate actually resolves.

- [ ] **Step 1: Add the `jaxrs.version` property and dependency management entry**

In `pom.xml`, replace:

```xml
        <cxf.version>3.6.8</cxf.version>
        <hamcrest.version>1.3</hamcrest.version>
        <java.version>17</java.version>
        <junit.version>4.13.1</junit.version>
```

with:

```xml
        <cxf.version>3.6.8</cxf.version>
        <hamcrest.version>1.3</hamcrest.version>
        <java.version>17</java.version>
        <jaxrs.version>2.1.1</jaxrs.version>
        <junit.version>4.13.1</junit.version>
```

Then replace:

```xml
            <dependency>
                <groupId>org.slf4j</groupId>
                <artifactId>slf4j-api</artifactId>
                <version>${slf4j-api.version}</version>
            </dependency>

            <!-- Test -->
```

with:

```xml
            <dependency>
                <groupId>org.slf4j</groupId>
                <artifactId>slf4j-api</artifactId>
                <version>${slf4j-api.version}</version>
            </dependency>
            <dependency>
                <groupId>javax.ws.rs</groupId>
                <artifactId>javax.ws.rs-api</artifactId>
                <version>${jaxrs.version}</version>
            </dependency>

            <!-- Test -->
```

- [ ] **Step 2: Add the `provided`-scope dependency to `plugin/pom.xml`**

In `plugin/pom.xml`, replace:

```xml
        <dependency>
            <groupId>javax.jws</groupId>
            <artifactId>javax.jws-api</artifactId>
            <version>1.1</version>
            <scope>provided</scope>
        </dependency>
```

with:

```xml
        <dependency>
            <groupId>javax.jws</groupId>
            <artifactId>javax.jws-api</artifactId>
            <version>1.1</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>javax.ws.rs</groupId>
            <artifactId>javax.ws.rs-api</artifactId>
            <scope>provided</scope>
        </dependency>
```

- [ ] **Step 3: Add the runtime bundle to `karaf-features.xml`**

In `karaf-features/src/main/resources/features.xml`, replace:

```xml
        <bundle>mvn:it.arsinfo.opennms.plugins/otrs6-plugin/${project.version}</bundle>
    </feature>

</features>
```

with:

```xml
        <bundle dependency="true">mvn:javax.ws.rs/javax.ws.rs-api/${jaxrs.version}</bundle>
        <bundle>mvn:it.arsinfo.opennms.plugins/otrs6-plugin/${project.version}</bundle>
    </feature>

</features>
```

- [ ] **Step 4: Run the full test suite**

Run: `mvn test -pl plugin`
Expected: PASS — no code changed, all 40 existing tests still pass, confirming the new `provided` dependency didn't break compilation.

- [ ] **Step 5: Run the full build**

Run: `mvn clean package`
Expected: BUILD SUCCESS across all 5 reactor modules, including the `assembly/kar` module's `karaf:verify` step — this is what actually confirms `mvn:javax.ws.rs/javax.ws.rs-api/2.1.1` resolves as a real bundle coordinate.

Note the limit of this verification: `mvn clean package` (and `karaf:verify`) confirm the Maven artifact resolves, but do not catch an OSGi `Import-Package` version-range mismatch the way `CLAUDE.md`'s JAX-WS section describes (bnd infers ranges from the compile-time jar; whether those ranges are satisfiable is only proven by an actual Karaf install). The reference project (`opennms-service-now-plugin`, a real deployed plugin against the same `opennms.api.version`) needed no `Import-Package` override for `javax.ws.rs` in its `maven-bundle-plugin` config, which is reasonable evidence none is needed here either — but if a live Karaf install ever surfaces a resolution error for `javax.ws.rs`/`javax.ws.rs.core`, the fix follows the same widening pattern already applied to `javax.jws`/`javax.xml.ws` in `plugin/pom.xml`.

- [ ] **Step 6: Commit**

```bash
git add pom.xml plugin/pom.xml karaf-features/src/main/resources/features.xml
git commit -m "feat: add javax.ws.rs-api runtime dependency for JAX-RS support"
```

---

### Task 2: `WebhookHandler` / `WebhookHandlerImpl`

**Files:**
- Create: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/rest/WebhookHandler.java`
- Create: `plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/rest/WebhookHandlerImpl.java`
- Create: `plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/rest/WebhookHandlerImplTest.java`

**Interfaces:**
- Consumes: `javax.ws.rs.GET`, `javax.ws.rs.Path`, `javax.ws.rs.core.Response` from Task 1's new dependency.
- Produces: `public interface WebhookHandler { Response ping(); }` and `public class WebhookHandlerImpl implements WebhookHandler` (no-arg constructor) — Task 3 wires `WebhookHandlerImpl` into blueprint.

- [ ] **Step 1: Write the failing tests**

Create `WebhookHandlerImplTest.java`:

```java
package it.arsinfo.opennms.plugins.otrs6.rest;

import org.junit.Test;

import javax.ws.rs.core.Response;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

public class WebhookHandlerImplTest {

    private final WebhookHandlerImpl handler = new WebhookHandlerImpl();

    @Test
    public void ping_returnsOkStatus() {
        Response response = handler.ping();

        assertThat(response.getStatus(), equalTo(Response.Status.OK.getStatusCode()));
    }

    @Test
    public void ping_returnsPongEntity() {
        Response response = handler.ping();

        assertThat(response.getEntity(), equalTo("pong"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=WebhookHandlerImplTest -pl plugin`
Expected: FAIL to compile — `cannot find symbol: class WebhookHandlerImpl` (neither production class exists yet).

- [ ] **Step 3: Write the minimal implementation**

Create `WebhookHandler.java`:

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

Create `WebhookHandlerImpl.java`:

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

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=WebhookHandlerImplTest -pl plugin`
Expected: PASS — 2 tests run, 0 failures.

- [ ] **Step 5: Run the full test suite**

Run: `mvn test -pl plugin`
Expected: PASS — all 42 tests (40 existing + 2 new) pass.

- [ ] **Step 6: Commit**

```bash
git add plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/rest/WebhookHandler.java plugin/src/main/java/it/arsinfo/opennms/plugins/otrs6/rest/WebhookHandlerImpl.java plugin/src/test/java/it/arsinfo/opennms/plugins/otrs6/rest/WebhookHandlerImplTest.java
git commit -m "feat: add WebhookHandler REST ping endpoint"
```

---

### Task 3: Wire `WebhookHandlerImpl` into blueprint

**Files:**
- Modify: `plugin/src/main/resources/OSGI-INF/blueprint/blueprint.xml`

**Interfaces:**
- Consumes: `WebhookHandlerImpl` (no-arg constructor) and `WebhookHandler` from Task 2.
- Produces: a blueprint bean `webhookHandlerImpl`, published as an OSGi `<service>` for `WebhookHandler` with `service-properties` `application-path=/rest`.

**Why no new test here:** Blueprint XML isn't unit-tested anywhere in this project (Aries Blueprint validation only happens at OSGi deploy time). Verification is: the file stays well-formed XML following the existing bean/service patterns already in the file, plus a full build.

- [ ] **Step 1: Make the change**

In `blueprint.xml`, replace:

```xml
    <bean id="pluginScheduler" class="it.arsinfo.opennms.plugins.otrs6.ticketing.PluginScheduler"
          init-method="start" destroy-method="stop">
        <argument ref="alarmTicketUpdater"/>
        <argument ref="otrsTicketDao"/>
    </bean>
    <service interface="org.opennms.integration.api.v1.health.HealthCheck" ref="pluginScheduler"/>

</blueprint>
```

with:

```xml
    <bean id="pluginScheduler" class="it.arsinfo.opennms.plugins.otrs6.ticketing.PluginScheduler"
          init-method="start" destroy-method="stop">
        <argument ref="alarmTicketUpdater"/>
        <argument ref="otrsTicketDao"/>
    </bean>
    <service interface="org.opennms.integration.api.v1.health.HealthCheck" ref="pluginScheduler"/>

    <bean id="webhookHandlerImpl" class="it.arsinfo.opennms.plugins.otrs6.rest.WebhookHandlerImpl"/>
    <service interface="it.arsinfo.opennms.plugins.otrs6.rest.WebhookHandler" ref="webhookHandlerImpl">
        <service-properties>
            <entry key="application-path" value="/rest"/>
        </service-properties>
    </service>

</blueprint>
```

- [ ] **Step 2: Run the full test suite**

Run: `mvn test -pl plugin`
Expected: PASS — all 42 tests still pass; no compile errors.

- [ ] **Step 3: Run the full build**

Run: `mvn clean package`
Expected: BUILD SUCCESS across all 5 reactor modules — confirms `blueprint.xml` is still well-formed and the bundle packages correctly.

- [ ] **Step 4: Commit**

```bash
git add plugin/src/main/resources/OSGI-INF/blueprint/blueprint.xml
git commit -m "feat: wire WebhookHandlerImpl into blueprint as a REST service"
```
