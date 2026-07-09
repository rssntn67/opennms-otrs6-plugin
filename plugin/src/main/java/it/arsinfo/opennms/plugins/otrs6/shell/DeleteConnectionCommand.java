package it.arsinfo.opennms.plugins.otrs6.shell;

import it.arsinfo.opennms.plugins.otrs6.connection.ConnectionManager;
import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;

@Command(scope = "opennms-otrs-6", name = "connection-delete", description = "Delete a connection", detailedDescription = "Deletes a connection for OTRS6")
@Service
public class DeleteConnectionCommand implements Action {

    @Reference
    private ConnectionManager connectionManager;


    @Override
    public Object execute() {
        if (this.connectionManager.deleteConnection()) {
            System.out.println("Connection deleted");
        } else {
            System.out.println("Connection not found");
        }
        return null;
    }
}
