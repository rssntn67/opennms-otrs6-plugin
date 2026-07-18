package it.arsinfo.opennms.plugins.otrs6.clients;

import it.arsinfo.opennms.plugins.otrs6.connection.Connection;
import it.arsinfo.opennms.plugins.otrs6.connection.ConnectionManager;
import org.junit.Before;
import org.junit.Test;

import java.util.Optional;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.*;

public class ClientManagerTest {

    private ClientManager clientManager;
    private ConnectionManager connectionManager;

    @Before
    public void setUp() {
        clientManager = new ClientManager();
        connectionManager = mock(ConnectionManager.class);
    }

    @Test
    public void getOtrsClient_returnsEmptyWhenConnectionAbsent() {
        when(connectionManager.getConnection()).thenReturn(Optional.empty());

        Optional<OtrsClient> client = clientManager.getOtrsClient(connectionManager);

        assertThat(client, equalTo(Optional.empty()));
    }

    @Test
    public void asClientCredentials_mapsConnectionFieldsCorrectly() {
        Connection connection = mock(Connection.class);
        when(connection.getUrl()).thenReturn("http://otrs.example.com");
        when(connection.getUsername()).thenReturn("user");
        when(connection.getPassword()).thenReturn("pass");
        when(connection.isIgnoreSslCertificateValidation()).thenReturn(true);

        ClientCredentials credentials = ClientManager.asClientCredentials(connection);

        assertThat(credentials.url, equalTo("http://otrs.example.com"));
        assertThat(credentials.username, equalTo("user"));
        assertThat(credentials.password, equalTo("pass"));
        assertThat(credentials.ignoreSslCertificateValidation, equalTo(true));
    }
}
