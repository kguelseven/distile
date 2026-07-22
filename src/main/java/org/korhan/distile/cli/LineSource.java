package org.korhan.distile.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Streams raw lines from stdin or a file to a consumer one at a time.
 * The memory stays bounded by input size. In tail mode, reaching EOF does not
 * end the stream; it means we have caught up, so we sleep briefly and keep
 * reading to pickup lines appended later.
 */
public final class LineSource {

    private static final long TAIL_IDLE_SLEEP_MS = 100;

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
     * interrupted), handing each to consumer.
     */
    public void forEachLine(Consumer<String> consumer) throws IOException {
        try (BufferedReader reader = openReader()) {
            boolean tailing = tail && file != null;
            while (true) {
                String line = reader.readLine();
                if (line != null) {
                    consumer.accept(line);
                    continue;
                }
                if (!tailing) {
                    return; // genuine EOF
                }
                // Caught up: wait for more appended data, unless we're shutting down.
                try {
                    Thread.sleep(TAIL_IDLE_SLEEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private BufferedReader openReader() throws IOException {
        if (file == null) {
            return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        }
        return Files.newBufferedReader(file, StandardCharsets.UTF_8);
    }
}
