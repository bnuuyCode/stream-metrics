package io.github.bnuuycode.streammetrics.web;

/**
 * How much a value can be trusted right now.
 *
 * <p>This enum is small but it is the backbone of the project's core rule
 * (DECISIONS.md § 3): a number is never shown without saying how fresh it is.
 * Every value that reaches the screen carries one of these three states.
 */
public enum Freshness {

    /** Fetched just now, straight from the platform. Trust it. */
    OK,

    /**
     * We do have a value, but it is knowingly old — the cache expired and the
     * refresh did not succeed. It may be displayed, but only clearly marked as
     * stale. Never dressed up as current.
     */
    STALE,

    /**
     * No value at all. The fetch failed and there is nothing trustworthy to
     * show.
     *
     * <p>Note what this deliberately does <em>not</em> do: fall back to the
     * last snapshot in the database. That fallback is exactly the behaviour
     * DECISIONS.md § 4 forbids — it would turn a visible failure into a silent
     * lie.
     */
    ERROR
}
