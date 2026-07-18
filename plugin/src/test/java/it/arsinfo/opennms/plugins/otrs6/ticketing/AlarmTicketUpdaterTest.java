package it.arsinfo.opennms.plugins.otrs6.ticketing;

import it.arsinfo.opennms.plugins.otrs6.clients.ClientManager;
import it.arsinfo.opennms.plugins.otrs6.clients.OtrsClient;
import it.arsinfo.opennms.plugins.otrs6.connection.ConnectionManager;
import org.junit.Before;
import org.junit.Test;
import org.opennms.integration.api.v1.dao.AlarmDao;
import org.opennms.integration.api.v1.model.Alarm;
import org.opennms.integration.api.v1.ticketing.Ticket;
import org.opennms.integration.api.v1.ticketing.immutables.ImmutableTicket;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class AlarmTicketUpdaterTest {

    private ClientManager clientManager;
    private ConnectionManager connectionManager;
    private AlarmDao alarmDao;
    private OtrsClient otrsClient;
    private AlarmTicketUpdater updater;

    @Before
    public void setUp() {
        clientManager = mock(ClientManager.class);
        connectionManager = mock(ConnectionManager.class);
        alarmDao = mock(AlarmDao.class);
        otrsClient = mock(OtrsClient.class);
        updater = new AlarmTicketUpdater(clientManager, connectionManager, alarmDao);

        when(clientManager.getOtrsClient(connectionManager)).thenReturn(Optional.of(otrsClient));
    }

    @Test
    public void run_skipsWhenNoConnectionConfigured() {
        when(clientManager.getOtrsClient(connectionManager)).thenReturn(Optional.empty());

        updater.run();

        verify(alarmDao, never()).getAlarms();
    }

    @Test
    public void run_skipsAlarmWithNullTicketId() {
        Alarm alarm = mock(Alarm.class);
        when(alarm.getTicketId()).thenReturn(null);
        when(alarmDao.getAlarms()).thenReturn(List.of(alarm));

        updater.run();

        verify(otrsClient, never()).get(anyString());
    }

    @Test
    public void run_skipsAlarmWithBlankTicketId() {
        Alarm alarm = mock(Alarm.class);
        when(alarm.getTicketId()).thenReturn("");
        when(alarmDao.getAlarms()).thenReturn(List.of(alarm));

        updater.run();

        verify(otrsClient, never()).get(anyString());
    }

    @Test
    public void run_updatesAlarmWhenTicketStateChanged() {
        Alarm alarm = mock(Alarm.class);
        when(alarm.getId()).thenReturn(7);
        when(alarm.getTicketId()).thenReturn("1");
        when(alarm.getTicketState()).thenReturn(Ticket.State.OPEN);
        when(alarmDao.getAlarms()).thenReturn(List.of(alarm));

        Ticket ticket = ImmutableTicket.newBuilder().setId("1").setSummary("s").setState(Ticket.State.CLOSED).build();
        when(otrsClient.get("1")).thenReturn(ticket);

        updater.run();

        verify(alarmDao, times(1)).setTicketState(Ticket.State.CLOSED, 7);
    }

    @Test
    public void run_doesNotUpdateAlarmWhenTicketStateUnchanged() {
        Alarm alarm = mock(Alarm.class);
        when(alarm.getId()).thenReturn(7);
        when(alarm.getTicketId()).thenReturn("1");
        when(alarm.getTicketState()).thenReturn(Ticket.State.OPEN);
        when(alarmDao.getAlarms()).thenReturn(List.of(alarm));

        Ticket ticket = ImmutableTicket.newBuilder().setId("1").setSummary("s").setState(Ticket.State.OPEN).build();
        when(otrsClient.get("1")).thenReturn(ticket);

        updater.run();

        verify(alarmDao, never()).setTicketState(any(Ticket.State.class), anyInt());
    }

    @Test
    public void run_skipsAlarmWhenTicketNoLongerExists() {
        Alarm alarm = mock(Alarm.class);
        when(alarm.getId()).thenReturn(7);
        when(alarm.getTicketId()).thenReturn("1");
        when(alarmDao.getAlarms()).thenReturn(List.of(alarm));
        when(otrsClient.get("1")).thenReturn(null);

        updater.run();

        verify(alarmDao, never()).setTicketState(any(Ticket.State.class), anyInt());
    }

    @Test
    public void run_continuesProcessingAfterOneAlarmFails() {
        Alarm failing = mock(Alarm.class);
        when(failing.getId()).thenReturn(1);
        when(failing.getTicketId()).thenReturn("1");
        when(otrsClient.get("1")).thenThrow(new RuntimeException("boom"));

        Alarm healthy = mock(Alarm.class);
        when(healthy.getId()).thenReturn(2);
        when(healthy.getTicketId()).thenReturn("2");
        when(healthy.getTicketState()).thenReturn(Ticket.State.OPEN);
        Ticket ticket = ImmutableTicket.newBuilder().setId("2").setSummary("s").setState(Ticket.State.CLOSED).build();
        when(otrsClient.get("2")).thenReturn(ticket);

        when(alarmDao.getAlarms()).thenReturn(List.of(failing, healthy));

        updater.run();

        verify(alarmDao, times(1)).setTicketState(Ticket.State.CLOSED, 2);
    }
}
