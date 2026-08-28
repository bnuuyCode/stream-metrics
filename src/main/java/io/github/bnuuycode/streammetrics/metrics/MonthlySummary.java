package io.github.bnuuycode.streammetrics.metrics;

import io.github.bnuuycode.streammetrics.db.SnapshotRepository;
import io.github.bnuuycode.streammetrics.db.StreamRepository;
import io.github.bnuuycode.streammetrics.db.StreamRepository.SessionTotals;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Adds a calendar month of history into one set of totals.
 *
 * <p>The month is closed rather than rolling, because the point of these numbers
 * is comparing one month against the last. It is also the boundary a person
 * already thinks in.
 *
 * <p>Nothing here comes from an API. Every figure is built from data this
 * application collected itself, which is exactly why the summary carries its own
 * coverage: a total assembled from incomplete sampling looks identical to a
 * complete one unless it says otherwise.
 */
public final class MonthlySummary {

    private final StreamRepository streams;
    private final SnapshotRepository snapshots;
    private final ZoneId zone;

    public MonthlySummary(StreamRepository streams, SnapshotRepository snapshots, ZoneId zone) {
        this.streams = streams;
        this.snapshots = snapshots;
        this.zone = zone;
    }

    public Summary currentMonth(long accountId) {
        YearMonth month = YearMonth.now(zone);
        return forMonth(accountId, month);
    }

    private Summary forMonth(long accountId, YearMonth month) {
        LocalDate firstDay = month.atDay(1);
        LocalDate firstDayNext = month.plusMonths(1).atDay(1);

        List<SessionTotals> sessions = streams.findSessionsBetween(
                accountId,
                firstDay.atStartOfDay(zone).toInstant(),
                firstDayNext.atStartOfDay(zone).toInstant());

        long onAirMinutes = 0;
        double viewerMinutes = 0;
        long samplesTaken = 0;
        long samplesExpected = 0;
        Long peak = null;

        for (SessionTotals session : sessions) {
            onAirMinutes += session.onAir().toMinutes();
            viewerMinutes += session.viewerMinutes();
            samplesTaken += session.sampleCount();
            samplesExpected += session.expectedSamples();

            if (session.peakViewers() != null && (peak == null || session.peakViewers() > peak)) {
                peak = session.peakViewers();
            }
        }

        // Weighted by samples, not an average of averages. A ten-minute stream
        // must not weigh the same as a four-hour one.
        Double average = samplesTaken == 0 ? null : viewerMinutes / samplesTaken;

        return new Summary(
                month.toString(),
                month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.getYear(),
                sessions.size(),
                onAirMinutes,
                Math.round(viewerMinutes),
                peak,
                average,
                Coverage.of(samplesTaken, samplesExpected),
                growth(accountId, MetricKey.FOLLOWERS, firstDay, firstDayNext),
                growth(accountId, MetricKey.SUBSCRIBERS, firstDay, firstDayNext));
    }

    private Growth growth(long accountId, MetricKey metric, LocalDate from, LocalDate to) {
        Optional<SnapshotRepository.Growth> found = snapshots.growthBetween(accountId, metric, from, to);

        if (found.isEmpty()) {
            return null;
        }

        SnapshotRepository.Growth g = found.get();

        // Collection may have started partway through the month. Saying "+47 in
        // August" when the first reading is from the 27th would credit the whole
        // month with growth measured over four days.
        boolean partial = !g.fromDate().equals(from.toString());

        return new Growth(g.delta(), g.fromDate(), partial);
    }

    /**
     * @param onAirMinutes  from Twitch's own start times — solid
     * @param viewerMinutes viewers multiplied by minutes; an estimate, only as
     *                      complete as {@code coverage} says
     * @param peakViewers   the highest single sample, so a spike between two
     *                      samples is invisible to it
     */
    public record Summary(
            String month,
            String label,
            int streamCount,
            long onAirMinutes,
            long viewerMinutes,
            Long peakViewers,
            Double averageViewers,
            Coverage coverage,
            Growth followers,
            Growth subscribers) {
    }

    /**
     * How much of what should have been measured actually was.
     *
     * <p>Worth being precise about what this does and does not claim. It reports
     * whether <em>our</em> sampling was complete. It says nothing about whether
     * the numbers Twitch handed us were right — a stream sampled perfectly from
     * start to finish reads as full coverage even if every count was wrong at the
     * source. We can be transparent about our own work; we cannot audit theirs.
     */
    public record Coverage(long samplesTaken, long samplesExpected, Integer percent, String level) {

        /**
         * Thresholds are stated here in the open rather than hidden behind a
         * single computed score. A number like "confidence: 87%" sounds precise
         * and means nothing unless someone can say what it counted.
         *
         * <p>Derived at response time, never stored. The rule against keeping
         * computed values applies to the database, and for good reason — but a
         * value assembled fresh for one reply cannot go stale.
         */
        public static Coverage of(long samplesTaken, long samplesExpected) {
            if (samplesExpected == 0) {
                return new Coverage(samplesTaken, samplesExpected, null, "NONE");
            }

            int percent = (int) Math.round(100.0 * samplesTaken / samplesExpected);
            String level = percent >= 95 ? "FULL" : percent >= 70 ? "PARTIAL" : "LOW";

            return new Coverage(samplesTaken, samplesExpected, percent, level);
        }
    }

    /**
     * @param sinceDate the first day actually measured
     * @param partial   true when measurement began after the month did
     */
    public record Growth(long delta, String sinceDate, boolean partial) {
    }
}
