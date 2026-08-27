package io.github.bnuuycode.streammetrics.metrics;

import java.util.List;
import java.util.Optional;

/**
 * A platform this application can read numbers from.
 *
 * <p>This is the seam that keeps the project modular. Adding YouTube means
 * writing one class that implements this interface and adding one line where
 * the providers are listed — nothing already written gets touched.
 *
 * <p>Deliberately not a plugin system. Real plugins (jars discovered at
 * runtime, isolated classloaders, lifecycle management) exist so that third
 * parties can extend an application without recompiling it. Nobody here needs
 * that, and the machinery would cost far more than it returns. An interface
 * buys the same modularity for nothing.
 *
 * <p>A provider knows what to collect. It knows nothing about when to run,
 * about retries, or about how failures get recorded — that belongs to the
 * scheduler, and keeping the two apart is what lets either change alone.
 */
public interface MetricsProvider {

    /** Matches the {@code platform} column: "twitch", "youtube", ... */
    String platform();

    /**
     * The row in {@code account} these numbers belong to, or empty when nobody
     * has connected this platform yet.
     *
     * <p>Empty is a normal state, not a failure: the collector records the
     * attempt as SKIPPED and moves on.
     */
    Optional<Long> accountId();

    /**
     * Reads every metric this platform can currently report.
     *
     * @throws CollectionException when the platform could not be read. The
     *                             exception carries a category so the collector
     *                             can tell a dead token apart from a passing
     *                             network blip.
     */
    List<MetricSample> snapshot();
}
