package it.arsinfo.opennms.plugins.otrs6.ticketing;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.opennms.integration.api.v1.health.Context;
import org.opennms.integration.api.v1.health.Response;
import org.opennms.integration.api.v1.health.Status;

import java.time.Duration;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.*;

public class PluginSchedulerTest {

    private AlarmTicketUpdater alarmTicketUpdater;
    private OtrsTicketDao otrsTicketDao;
    private Context context;
    private PluginScheduler scheduler;

    @Before
    public void setUp() {
        alarmTicketUpdater = mock(AlarmTicketUpdater.class);
        otrsTicketDao = mock(OtrsTicketDao.class);
        context = mock(Context.class);
        scheduler = new PluginScheduler(alarmTicketUpdater, otrsTicketDao, Duration.ofMillis(50));
    }

    @After
    public void tearDown() {
        scheduler.stop();
    }

    @Test
    public void start_runsBothTasksRepeatedlyOnOneThread() {
        scheduler.start();

        verify(alarmTicketUpdater, timeout(1000).atLeast(2)).run();
        verify(otrsTicketDao, timeout(1000).atLeast(2)).run();
    }

    @Test
    public void stop_afterStartDoesNotThrow() {
        scheduler.start();

        scheduler.stop();
    }

    @Test
    public void stop_beforeStartDoesNotThrow() {
        scheduler.stop();
    }

    @Test
    public void stop_calledTwiceDoesNotThrow() {
        scheduler.start();

        scheduler.stop();
        scheduler.stop();
    }

    @Test
    public void getDescription_returnsNonBlankString() {
        String description = scheduler.getDescription();

        assertThat(description, notNullValue());
        assertThat(description.isBlank(), equalTo(false));
    }

    @Test
    public void perform_reportsStartingBeforeStart() {
        Response response = scheduler.perform(context);

        assertThat(response.getStatus(), equalTo(Status.Starting));
    }

    @Test
    public void perform_reportsSuccessWhileRunning() {
        scheduler.start();

        Response response = scheduler.perform(context);

        assertThat(response.getStatus(), equalTo(Status.Success));
    }

    @Test
    public void perform_reportsFailureWhenATaskFutureIsDone() {
        scheduler.start();
        scheduler.stop();

        Response response = scheduler.perform(context);

        assertThat(response.getStatus(), equalTo(Status.Failure));
    }
}
