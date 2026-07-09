package it.arsinfo.opennms.plugins.otrs6.shell;

import it.arsinfo.opennms.plugins.otrs6.connection.Connection;
import it.arsinfo.opennms.plugins.otrs6.connection.ConnectionManager;
import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.apache.karaf.shell.api.console.Session;
import org.apache.karaf.shell.support.table.Col;
import org.apache.karaf.shell.support.table.ShellTable;

@Command(scope = "opennms-otrs-6", name = "connection-get", description = "List existing connections", detailedDescription = "List all existing connections for OTRS6")
@Service
public class GetConnectionCommand implements Action {

    @Reference
    private Session session;

    @Reference
    private ConnectionManager connectionManager;

    @Override
    public Object execute() {
        final var table = new ShellTable()
                .size(session.getTerminal().getWidth() - 1)
                .column(new Col("url").maxSize(72))
                .column(new Col("username").maxSize(36))
                .column(new Col("password").maxSize(36))
                .column(new Col("ignoreSslVal").maxSize(12))
                ;

        Connection connection= this.connectionManager.getConnection().orElseThrow();
        final var row = table.addRow();
        row.addContent(connection.getUrl());
        row.addContent(connection.getUsername());
        row.addContent("*****");
        row.addContent(connection.isIgnoreSslCertificateValidation());

        table.print(System.out, true);

        return null;
    }
}
