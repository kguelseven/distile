package org.korhan.distile.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Streams raw lines from stdin or a file into a consumer, one at a time.
 *
 * <p>Streaming, never slurping: memory stays bounded by template count, not by
 * input size. In tail mode, a {@code null} from {@code readLine()} means "caught
 * up to EOF" rather than "done" — we sleep briefly and keep reading so appended
 * lines are picked up. Log-rotation / inode-change handling is intentionally out
 * of scope here (that is adapter 2 in the roadmap).
 */
public final class LineSource {

    private static final long TAIL_IDLE_SLEEP_MS = 100;

    private final Path file;   // null => stdin
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
     * interrupted), handing each to {@code consumer}.
     */
    public void forEachLine(Consumer<String> consumer) throws IOException {
        try (BufferedReader reader = openReader()) {
            boolean tailing = tail && file != null; // tailing stdin is meaningless
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
