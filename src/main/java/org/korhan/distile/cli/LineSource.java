package org.korhan.distile.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Streams raw lines from stdin or a file to a LineSink one at a time.
 * The memory stays bounded by input size. In tail mode, reaching EOF does not
 * end the stream; it means we have caught up, so we sleep briefly and keep
 * reading to pickup lines appended later.
 */
public final class LineSource {

    private static final long TAIL_IDLE_SLEEP_MS = 100;

    /**
     * How long to wait for a continuation line before releasing a sink's held line.
     * Only ever paid when the sink is actually holding something back, so a sink that
     * never holds (the default) reads exactly as it did before joining existed.
     */
    private static final long PENDING_GRACE_MS = 50;

    private final Path file;
    private final boolean tail;

    private LineSource(Path file, boolean tail) {
        this.file = file;
        this.tail = tail;
    }

    public static LineSource ofStdin() {
        return new LineSource(null, false);
    }

    public static LineSource ofFile(Path file, boolean tail) {
        return new LineSource(file, tail);
    }

    /**
     * Read lines until end-of-input (or, in tail mode, until the thread is
     * interrupted), handing each to sink.
     */
    public void forEachLine(LineSink sink) throws IOException {
        try (BufferedReader reader = openReader()) {
            boolean tailing = tail && file != null;
            while (true) {
                if (!releasePendingIfIdle(reader, sink)) {
                    return; // interrupted while waiting
                }
                String line = reader.readLine();
                if (line != null) {
                    sink.accept(line);
                    continue;
                }
                if (!tailing) {
                    sink.onEnd();
                    return; // genuine EOF
                }
                // Caught up: nothing more is coming for now, so a held line must not
                // wait for the app's next write — which on an idle stream may never come.
                sink.onIdle();
                // Wait for more appended data, unless we're shutting down.
                try {
                    Thread.sleep(TAIL_IDLE_SLEEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sink.onEnd();
                    return;
                }
            }
        }
    }

    /**
     * Release a held line once the stream goes quiet, after a short grace period.
     *
     * <p>Costs nothing when nothing is held, and nothing on a saturated pipe where ready()
     * is true. ready() can go false transiently between buffer refills; the re-check makes
     * a premature release unlikely, and its worst case is one split record.
     *
     * @return false if interrupted (the caller should stop)
     */
    private static boolean releasePendingIfIdle(BufferedReader reader, LineSink sink) throws IOException {
        if (!sink.hasPending() || reader.ready()) {
            return true;
        }
        try {
            Thread.sleep(PENDING_GRACE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sink.onEnd();
            return false;
        }
        if (!reader.ready()) {
            sink.onIdle();
        }
        return true;
    }

    private BufferedReader openReader() throws IOException {
        if (file == null) {
            return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        }
        return Files.newBufferedReader(file, StandardCharsets.UTF_8);
    }
}
