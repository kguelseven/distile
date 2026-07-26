package org.korhan.distile.cli;

/**
 * Where LineSource hands the lines it reads.
 *
 * <p>A plain Consumer would do for a one-line-per-record stream, but StackTraceJoiner has
 * to hold a line back until the next one proves it is not a continuation. The extra
 * callbacks are how it learns no next line is coming — without them a held line waits for
 * the app to log again, which on an idle stream is forever.
 *
 * <p>The defaults describe a sink that never holds anything back, so the unjoined path
 * behaves as it did before joining existed. Not thread-safe: ingest thread only.
 */
public interface LineSink {

    /** Consume one line. */
    void accept(String line);

    /** True while a line is held back awaiting the next one. */
    default boolean hasPending() {
        return false;
    }

    /** No further input is readily available — release anything held back. */
    default void onIdle() {
    }

    /** End of input — release anything held back. */
    default void onEnd() {
    }
}
