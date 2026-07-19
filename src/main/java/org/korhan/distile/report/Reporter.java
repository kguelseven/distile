package org.korhan.distile.report;

import org.korhan.distile.emission.EmissionEvent;

/**
 * Turns emission events into user-facing output. This is the ONLY place
 * formatting lives — core and emission never build strings for the user.
 * Swapping text for JSON is a single choice of implementation.
 */
public interface Reporter {

    /** Render and write one emission event. */
    void emit(EmissionEvent event);

    /** Flush any buffered output (called on shutdown). */
    default void flush() {
    }
}
