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

There is no `.cfg` file. The OTRS connection (URL, username, password,
whether to ignore SSL certificate validation) is stored in OpenNMS's Secure
Credentials Vault and managed via Karaf shell commands, scope `opennms-otrs-6`:

```
opennms-otrs-6:connection-add <url> <username> <password>
opennms-otrs-6:connection-get
opennms-otrs-6:connection-validate
opennms-otrs-6:connection-delete
```

Run `opennms-otrs-6:connection-add --help` for the full set of options
(`-f`/`--force` to skip live validation, `-t`/`--test` for a dry run,
`-i`/`--ignore-ssl-certificate-validation`).
