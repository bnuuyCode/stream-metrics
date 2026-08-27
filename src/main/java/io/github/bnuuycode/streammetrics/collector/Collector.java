package io.github.bnuuycode.streammetrics.collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs the background jobs and owns the thread they run on.
 *
 * <p>Two daemon threads, no framework. For a handful of scheduled tasks on a
 * personal machine, {@link ScheduledExecutorService} from the standard library
 * is the whole answer — a scheduling library here would be more configuration
 * than code.
 */
public final class Collector {

    private static final Logger log = LoggerFactory.getLogger(Collector.class);

    private final SnapshotJob snapshotJob;
    private final LiveSampler liveSampler;
    private final ScheduledExecutorService executor;

    public Collector(SnapshotJob snapshotJob, LiveSampler liveSampler) {
        this.snapshotJob = snapshotJob;
        this.liveSampler = liveSampler;

        this.executor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "collector");
            // Daemon threads: closing the application must not be held up by a
            // sampler waiting for its next minute to elapse.
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        // Anything left open by a previous run that died mid-broadcast.
        liveSampler.closeAbandonedSessions();

        // Immediately, then hourly. The first run is what catches up after the
        // machine has been off — no separate catch-up path to get wrong.
        executor.scheduleAtFixedRate(safely(snapshotJob), 0, 1, TimeUnit.HOURS);

        scheduleNextSample(0);

        log.info("Collector started");
    }

    /**
     * Re-schedules itself after each poll, because the interval changes with
     * what it finds: one minute while live, five while off air.
     */
    private void scheduleNextSample(long delaySeconds) {
        executor.schedule(() -> {
            try {
                liveSampler.run();
            } catch (RuntimeException e) {
                log.error("Live sampler failed", e);
            } finally {
                // In the finally block on purpose. Miss this and a single
                // unexpected exception silently ends live sampling for the rest
                // of the session, with nothing on screen to say so.
                scheduleNextSample(liveSampler.nextDelay().toSeconds());
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    /**
     * Wraps a repeating task so an exception cannot kill it.
     *
     * <p>{@code scheduleAtFixedRate} cancels a task forever the first time it
     * throws, and says nothing. A collector that stops collecting without
     * telling anyone is exactly the failure this project exists to prevent.
     */
    private static Runnable safely(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("Scheduled task failed", e);
            }
        };
    }

    public void stop() {
        executor.shutdownNow();
    }
}
