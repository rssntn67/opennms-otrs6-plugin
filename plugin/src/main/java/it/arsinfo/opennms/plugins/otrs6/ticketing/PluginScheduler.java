package it.arsinfo.opennms.plugins.otrs6.ticketing;

import org.opennms.integration.api.v1.health.Context;
import org.opennms.integration.api.v1.health.HealthCheck;
import org.opennms.integration.api.v1.health.Response;
import org.opennms.integration.api.v1.health.Status;
import org.opennms.integration.api.v1.health.immutables.ImmutableResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class PluginScheduler implements HealthCheck {
    private static final Logger LOG = LoggerFactory.getLogger(PluginScheduler.class);
    private static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(5);

    private final AlarmTicketUpdater alarmTicketUpdater;
    private final OtrsTicketDao otrsTicketDao;
    private final Duration interval;

    private volatile ScheduledExecutorService executor;
    private volatile ScheduledFuture<?> alarmTicketUpdaterFuture;
    private volatile ScheduledFuture<?> otrsTicketDaoFuture;

    public PluginScheduler(AlarmTicketUpdater alarmTicketUpdater, OtrsTicketDao otrsTicketDao) {
        this(alarmTicketUpdater, otrsTicketDao, DEFAULT_INTERVAL);
    }

    PluginScheduler(AlarmTicketUpdater alarmTicketUpdater, OtrsTicketDao otrsTicketDao, Duration interval) {
        this.alarmTicketUpdater = alarmTicketUpdater;
        this.otrsTicketDao = otrsTicketDao;
        this.interval = interval;
    }

    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "otrs6-plugin-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        long periodMillis = interval.toMillis();
        alarmTicketUpdaterFuture = executor.scheduleWithFixedDelay(alarmTicketUpdater, 0, periodMillis, TimeUnit.MILLISECONDS);
        otrsTicketDaoFuture = executor.scheduleWithFixedDelay(otrsTicketDao, 0, periodMillis, TimeUnit.MILLISECONDS);
        LOG.info("PluginScheduler started, running tasks every {}", interval);
    }

    public void stop() {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOG.info("PluginScheduler stopped");
    }

    @Override
    public String getDescription() {
        return "OTRS6 Plugin Scheduler";
    }

    @Override
    public Response perform(Context context) {
        if (alarmTicketUpdaterFuture == null || otrsTicketDaoFuture == null) {
            return ImmutableResponse.newBuilder()
                    .setStatus(Status.Starting)
                    .setMessage("Not started")
                    .build();
        }
        boolean done = alarmTicketUpdaterFuture.isDone() || otrsTicketDaoFuture.isDone();
        return ImmutableResponse.newBuilder()
                .setStatus(done ? Status.Failure : Status.Success)
                .setMessage(done ? "Not running" : "Running")
                .build();
    }
}
