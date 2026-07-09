# OpenNMS OTRS6 Ticketing Plugin

An OpenNMS integration plugin, packaged as an OSGi bundle for deployment in
Apache Karaf, that implements OpenNMS's `TicketingPlugin` API backed by an
OTRS6 instance via the `GenericTicketConnector` SOAP interface.

## Build

```
mvn clean install
```

## Deploy to OpenNMS/Karaf

Option 1: Karaf feature install
```
feature:repo-add mvn:it.arsinfo.opennms.plugins/karaf-features/0.1.0-SNAPSHOT/xml
feature:install opennms-plugins-otrs6
```

Option 2: Copy KAR bundle directly
```
cp assembly/kar/target/opennms-otrs6-plugin.kar /opt/opennms/deploy/
```

## Configuration

Runtime configuration is read from `$OPENNMS_HOME/etc/it.arsinfo.opennms.plugins.otrs6.cfg`
with hot-reload support:
- `otrsUrl` — OTRS `GenericTicketConnector` SOAP endpoint (default: `http://127.0.0.1/otrs/nph-genericinterface.pl/Webservice/GenericTicketConnector`)
- `otrsUser` — OTRS user login (default: `root@localhost`)
- `otrsPassword` — OTRS user password (default: `root`)

## Known Limitations

The `karaf-features` module's Karaf feature verification (`karaf-maven-plugin:verify`)
is currently skipped. The generated SOAP client stubs only need `javax.jws` /
`javax.xml.ws` at compile time (`provided` scope), but resolving a working
JAX-WS runtime inside Karaf at deploy time requires wiring in an OSGi JAX-WS
implementation (e.g. Karaf's `cxf`/`cxf-jaxws` feature), which isn't done yet.
Deploying this plugin to a live Karaf instance will need that runtime wiring
added first.
