
package it.arsinfo.opennms.plugins.otrs6.clients;

import it.arsinfo.opennms.plugins.otrs6.connection.Connection;
import it.arsinfo.opennms.plugins.otrs6.connection.ConnectionValidationError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

public class ClientManager {
    private static final Logger LOG = LoggerFactory.getLogger(ClientManager.class);

    private ClientCredentials credentials;
    private OtrsClient client;
    public OtrsClient getOtrsClient(ClientCredentials credentials) {
        if (credentials.equals(this.credentials)) {
            return client;
        }
        this.credentials = credentials;
        this.client = new Otrs6Client(credentials);
        return client;
    }

    public Optional<ConnectionValidationError> validate(Connection connection) {
        LOG.warn("validate: {}", connection);
        try {
            ClientCredentials credentials = asApiClientCredentials(connection);
            LOG.warn("validate: {}", credentials);
            return Optional.empty();
        } catch (Exception e) {
            LOG.error("validate: {} failed", connection, e);
        }
        return Optional.of(new ConnectionValidationError("Connection could not be validated"));
    }

    public static ClientCredentials asApiClientCredentials(Connection connection) {
        return ClientCredentials.builder()
                .withUsername(connection.getUsername())
                .withPassword(connection.getPassword())
                .withUrl(connection.getUrl())
                .withIgnoreSslCertificateValidation(connection.isIgnoreSslCertificateValidation())
                .build();
    }

}
