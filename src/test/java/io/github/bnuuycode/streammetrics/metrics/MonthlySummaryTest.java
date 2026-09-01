package io.github.bnuuycode.streammetrics.metrics;

import io.github.bnuuycode.streammetrics.metrics.MonthlySummary.Coverage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Guards the arithmetic behind the monthly totals.
 *
 * <p>These numbers are read as facts about a month of work, and every one of
 * them is a small calculation that would be wrong in a way nobody could see from
 * looking at the screen. A weighted mean computed as a plain mean still produces
 * a plausible number.
 */
class MonthlySummaryTest {

    @Test
    @DisplayName("coverage is samples taken against samples expected")
    void coverageIsARatio() {
        Coverage coverage = Coverage.of(8, 11);

        assertEquals(73, coverage.percent());
        assertEquals("PARTIAL", coverage.level());
    }

    @Test
    @DisplayName("complete sampling reads as full")
    void fullCoverage() {
        assertEquals("FULL", Coverage.of(240, 240).level());
        assertEquals(100, Coverage.of(240, 240).percent());
    }

    @Test
    @DisplayName("the thresholds sit where they are documented")
    void thresholds() {
        // Stated in the open rather than hidden behind a single score, so they
        // can be argued with.
        assertEquals("FULL", Coverage.of(95, 100).level());
        assertEquals("PARTIAL", Coverage.of(94, 100).level());
        assertEquals("PARTIAL", Coverage.of(70, 100).level());
        assertEquals("LOW", Coverage.of(69, 100).level());
    }

    @Test
    @DisplayName("nothing expected is not zero coverage")
    void nothingToCover() {
        Coverage coverage = Coverage.of(0, 0);

        // A month with no broadcasts has no coverage to report. Rendering that
        // as 0% would read as a failure to measure something, when there was
        // nothing to measure.
        assertNull(coverage.percent());
        assertEquals("NONE", coverage.level());
    }

    @Test
    @DisplayName("more samples than expected is still just complete")
    void coverageIsCapped() {
        // Observed in the wild: an eight-minute-eleven-second broadcast expects
        // eight samples in whole minutes, and a restart mid-broadcast took an
        // extra one seconds after the last. That produced "125% coverage", which
        // reads as broken arithmetic rather than as complete data.
        Coverage coverage = Coverage.of(10, 8);

        assertEquals(100, coverage.percent());
        assertEquals("FULL", coverage.level());

        // The raw counts are still there, so the cap hides nothing.
        assertEquals(10, coverage.samplesTaken());
        assertEquals(8, coverage.samplesExpected());
    }

    @Test
    @DisplayName("a stream with no samples still counts as uncovered, not absent")
    void sampledNothing() {
        Coverage coverage = Coverage.of(0, 240);

        assertEquals(0, coverage.percent());
        assertEquals("LOW", coverage.level());
    }
}
