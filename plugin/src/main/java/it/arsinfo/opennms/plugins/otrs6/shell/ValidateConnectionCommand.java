package it.arsinfo.opennms.plugins.otrs6.shell;

import it.arsinfo.opennms.plugins.otrs6.clients.ClientManager;
import it.arsinfo.opennms.plugins.otrs6.connection.ConnectionManager;
import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;

@Command(scope = "opennms-otrs-6", name = "connection-validate", description = "Validate a connection", detailedDescription = "Validate an existing connection for OTRS6")
@Service
public class ValidateConnectionCommand implements Action {

    @Reference
    private ConnectionManager connectionManager;

    @Reference
    private ClientManager clientManager;

    @Override
    public Object execute() {
        final var connection = this.connectionManager.getConnection();
        if (connection.isEmpty()) {
            System.err.println("No connection exists!");
            return null;
        }

        final var error = clientManager.validate(connection.get());
        if (error.isPresent()) {
            System.err.println("Connection invalid: " + error.get().message);
        } else {
            System.out.println("Connection is valid");
        }

        return null;
    }
}
