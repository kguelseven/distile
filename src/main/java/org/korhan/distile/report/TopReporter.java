package org.korhan.distile.report;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.spi.SystemStream;
import org.jline.terminal.spi.TerminalProvider;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp;
import org.korhan.distile.core.LogCluster;
import org.korhan.distile.emission.EmissionEvent;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A top-like live view: a full-screen, in-place refreshing table of the frequency-ranked
 * templates. Each Snapshot (from the snapshot timer) is one frame; the screen is redrawn, not
 * appended. On POSIX the terminal attaches to the controlling terminal, so the view works even
 * when stdin is the log pipe (tail -f app.log | distile --top).
 *
 * <p>When output is not a real terminal (redirected to a file, or a dumb terminal) it degrades
 * gracefully: snapshots and the final report are delegated to a plain TextReporter instead of
 * escape codes. New-template and milestone events are intentionally silent in top mode — the
 * point is the snapshot view, not a scroll of per-event lines. The interactive view exits
 * cleanly (Ctrl-C just closes the alternate screen); the end-of-stream summary is printed only
 * in the degraded/redirected case, where that text is the whole output.
 *
 * <p>emit is synchronized: frames arrive on the snapshot daemon thread, the final report on the
 * shutdown-hook/main thread.
 */
public final class TopReporter implements Reporter {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final long refreshSeconds;
    private final Instant start;

    // Non-null only when we have a real terminal; otherwise we run degraded via textFallback.
    private final Terminal terminal;
    private final boolean degraded;
    private final TextReporter textFallback;

    private boolean started;                 // alt-screen entered

    // A row flashes bright when the template first enters the view, then fades back to default
    // over successive frames. FADE_RAMP is a descending cyan/teal ramp (256-colour indices):
    // index 0 is the fresh flash, later indices dimmer; past the end the row is the default
    // colour. Only new entries flash — mere count growth (almost every row, every frame) does not.
    private static final int[] FADE_RAMP = {51, 44, 37, 30};

    // Frame-to-frame state for the header bar's rate and "+new" deltas.
    private Instant prevAt;
    private long prevTotalLines;
    private int prevTotalTemplates;
    // clusterId -> last seen count, for detecting rows that are new or grew this frame.
    private final Map<Long, Long> prevCounts = new HashMap<>();
    // clusterId -> frames since it last changed, driving the fade (0 = just changed).
    private final Map<Long, Integer> fadeAge = new HashMap<>();

    public TopReporter(long refreshSeconds) {
        this.refreshSeconds = refreshSeconds;
        this.start = Instant.now();
        // We handle terminal-detection fallback ourselves, so silence JLine's own JUL warnings
        // (e.g. "Creating a dumb terminal") that would otherwise clutter the user's screen.
        Logger.getLogger("org.jline").setLevel(Level.SEVERE);

        Terminal t = buildTerminal();
        if (t == null) {
            this.terminal = null;
            this.degraded = true;
            this.textFallback = new TextReporter(System.out);
            System.err.println("[distile] --top: no interactive terminal detected; "
                    + "falling back to plain text output.");
        } else {
            this.terminal = t;
            this.degraded = false;
            this.textFallback = null;
        }
    }

    /**
     * Build a usable terminal for the live view, or null to signal the plain-text fallback.
     *
     * <p>A pipe on stdin (logs | distile --top) defeats the standard system terminal, which
     * binds to the redirected stdin fd and falls back to dumb. So we branch on what is
     * redirected: stdout not a terminal (e.g. > out.txt) returns null, keeping plain text;
     * stdin also a terminal uses the ordinary system terminal; stdin redirected attaches to the
     * controlling terminal via /dev/tty with the exec provider, which reads size/capabilities
     * from stty/infocmp regardless of stdin/stdout redirection.
     */
    private static Terminal buildTerminal() {
        try {
            TerminalProvider provider = detectProvider();
            boolean outTty = provider != null && provider.isSystemStream(SystemStream.Output);
            boolean inTty = provider != null && provider.isSystemStream(SystemStream.Input);
            if (provider != null && !outTty) {
                return null; // stdout redirected -> keep plain text
            }

            Terminal t;
            if (inTty || provider == null) {
                // Both streams are terminals (or we could not detect — best effort).
                t = TerminalBuilder.builder().system(true).build();
            } else {
                t = TerminalBuilder.builder()
                        .system(false)
                        .provider("exec")
                        .streams(new FileInputStream("/dev/tty"), new FileOutputStream("/dev/tty"))
                        .build();
            }
            if (isDumb(t) || t.getSize().getColumns() == 0) {
                t.close();
                return null;
            }
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    /** First loadable terminal provider (pure-Java exec preferred, then native jni), or null. */
    private static TerminalProvider detectProvider() {
        for (String name : new String[] {"exec", "jni"}) {
            try {
                return TerminalProvider.load(name);
            } catch (Throwable ignored) {
                // try the next provider
            }
        }
        return null;
    }

    private static boolean isDumb(Terminal t) {
        return Terminal.TYPE_DUMB.equals(t.getType()) || Terminal.TYPE_DUMB_COLOR.equals(t.getType());
    }

    @Override
    public synchronized void emit(EmissionEvent event) {
        switch (event) {
            // New-template and milestone events do not scroll into the full-screen view;
            // "what's new / what changed" is shown by the frame-to-frame highlighting instead.
            case EmissionEvent.NewTemplate ignored -> { }
            case EmissionEvent.Milestone ignored -> { }

            case EmissionEvent.Snapshot e -> {
                if (degraded) {
                    textFallback.emit(e);
                } else {
                    renderFrame(e);
                }
            }

            case EmissionEvent.Final e -> {
                if (degraded) {
                    // Not a live view (output redirected): the printed summary is the whole
                    // point, so emit it as plain text.
                    textFallback.emit(e);
                } else {
                    // Interactive top: exit cleanly. Just leave the alternate screen and restore
                    // the cursor — no final dump, since the user was watching the live view.
                    restoreTerminal();
                }
            }
        }
    }

    private void renderFrame(EmissionEvent.Snapshot e) {
        if (!started) {
            terminal.puts(InfoCmp.Capability.enter_ca_mode);
            terminal.puts(InfoCmp.Capability.cursor_invisible);
            // Disable auto-wrap for the duration of the view: even if the width were ever
            // misread, an over-long line is then clipped at the right margin instead of
            // wrapping and corrupting the frame. No-op on terminals lacking the capability.
            terminal.puts(InfoCmp.Capability.exit_am_mode);
            started = true;
        }

        Size size = terminal.getSize();
        int rows = size.getRows();
        int cols = size.getColumns();
        List<AttributedString> lines = buildFrame(e, rows, cols);

        // Full repaint from the top-left, clearing each row's tail (clr_eol) and any leftover
        // rows below the frame (clr_eos). Deliberately NOT JLine's diff-based Display: every
        // line is pre-truncated to the width so nothing can wrap, and this cursor-addressing is
        // robust across terminal types (incl. the /dev/tty exec-provider terminal) where the
        // diff renderer desynced on long lines.
        PrintWriter w = terminal.writer();
        terminal.puts(InfoCmp.Capability.cursor_home);
        for (int i = 0; i < lines.size() && i < rows; i++) {
            w.print(lines.get(i).toAnsi(terminal));
            terminal.puts(InfoCmp.Capability.clr_eol);
            if (i < lines.size() - 1) {
                w.print("\r\n");
            }
        }
        terminal.puts(InfoCmp.Capability.clr_eos);
        terminal.flush();

        // Advance frame-to-frame state for the next tick.
        prevAt = e.at();
        prevTotalLines = e.totalLines();
        prevTotalTemplates = e.totalTemplates();
        prevCounts.clear();
        for (LogCluster c : e.topN()) {
            prevCounts.put(c.clusterId(), c.count());
        }
    }

    /** Build the full frame: two-line header bar, a column header, then one row per template. */
    private List<AttributedString> buildFrame(EmissionEvent.Snapshot e, int rows, int cols) {
        // Leave the last column unused so a full-width line can never trigger auto-wrap.
        int width = Math.max(1, cols - 1);
        List<AttributedString> lines = new ArrayList<>();

        long rate = ratePerSecond(e.totalLines() - prevTotalLines, secondsBetween(prevAt, e.at()));
        if (prevAt == null) {
            // First frame: no previous point, so report the cumulative average instead.
            rate = ratePerSecond(e.totalLines(), secondsBetween(start, e.at()));
        }
        int fresh = newTemplates(e.totalTemplates(), prevTotalTemplates);

        // The header bar and column header are set apart from the data rows by colour (cyan).
        AttributedStyle accent = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);

        AttributedStringBuilder h1 = new AttributedStringBuilder();
        h1.style(accent.bold());
        h1.append("distile");
        h1.style(accent);
        h1.append("  ").append(CLOCK.format(e.at()))
                .append("   running ").append(elapsed(Duration.between(start, e.at())));
        lines.add(clip(h1.toAttributedString(), width));

        AttributedStringBuilder h2 = new AttributedStringBuilder();
        h2.style(accent);
        h2.append("lines ").append(String.format("%,d", e.totalLines()))
                .append(" (").append(String.format("%,d", rate)).append("/s)")
                .append("   templates ").append(String.format("%,d", e.totalTemplates()));
        if (fresh > 0) {
            h2.append(" (+").append(String.valueOf(fresh)).append(" new)");
        }
        h2.append("   showing top ").append(String.valueOf(e.topN().size()))
                .append("   every ").append(String.valueOf(refreshSeconds)).append("s");
        lines.add(clip(h2.toAttributedString(), width));

        lines.add(new AttributedString(""));

        AttributedString colHeader = new AttributedString(
                String.format("  %8s  %-5s %s", "COUNT", "ID", "TEMPLATE"),
                accent);
        lines.add(clip(colHeader, width));

        // Keep the table within the screen: header bar (2) + blank (1) + column header (1).
        int maxRows = Math.max(0, rows - 4);
        List<LogCluster> top = e.topN();
        Map<Long, Integer> newAges = new HashMap<>();
        for (int i = 0; i < top.size() && i < maxRows; i++) {
            LogCluster c = top.get(i);
            boolean isNew = isNewInView(c.clusterId(), prevCounts);
            // Reset to the fresh flash on entry, otherwise step one frame further into the fade.
            int age = isNew ? 0 : fadeAge.getOrDefault(c.clusterId(), FADE_RAMP.length) + 1;
            newAges.put(c.clusterId(), age);
            lines.add(row(c, fadeStyle(age), width));
        }
        // Rebuild from the visible rows each frame, so the map stays bounded to the top-N.
        fadeAge.clear();
        fadeAge.putAll(newAges);
        return lines;
    }

    /** One table row: "count  #id  template", template truncated to the available width. */
    static AttributedString row(LogCluster c, AttributedStyle style, int width) {
        String prefix = String.format("  %8d  #%-4d ", c.count(), c.clusterId());
        int remaining = Math.max(0, width - prefix.length());
        String line = prefix + truncate(c.templateString(), remaining);
        // Clip as a final guard (e.g. a very narrow terminal where the prefix alone overflows).
        return clip(new AttributedString(line, style), width);
    }

    /** Fade colour for a row's age: a descending cyan ramp, then default once fully faded. */
    static AttributedStyle fadeStyle(int age) {
        if (age >= 0 && age < FADE_RAMP.length) {
            return AttributedStyle.DEFAULT.foreground(FADE_RAMP[age]);
        }
        return AttributedStyle.DEFAULT;
    }

    private void restoreTerminal() {
        if (started && terminal != null) {
            terminal.puts(InfoCmp.Capability.enter_am_mode); // re-enable auto-wrap
            terminal.puts(InfoCmp.Capability.cursor_normal);
            terminal.puts(InfoCmp.Capability.exit_ca_mode);
            terminal.flush();
        }
        if (terminal != null) {
            try {
                terminal.close();
            } catch (Exception ignored) {
                // best effort on shutdown
            }
        }
    }

    @Override
    public void flush() {
        if (degraded) {
            textFallback.flush();
        } else if (terminal != null) {
            terminal.flush();
        }
    }

    // ---- pure helpers (package-private, unit-tested without a terminal) ----

    /** Lines/sec over an interval; 0 when the interval is non-positive (avoids divide-by-zero). */
    static long ratePerSecond(long deltaLines, double deltaSeconds) {
        if (deltaSeconds <= 0) {
            return 0;
        }
        return Math.round(deltaLines / deltaSeconds);
    }

    /** New templates since the previous frame. Never negative (template count is monotonic). */
    static int newTemplates(int total, int prevTotal) {
        return Math.max(0, total - prevTotal);
    }

    /** True when the cluster was not in the previous frame's view — i.e. it just appeared. */
    static boolean isNewInView(long clusterId, Map<Long, Long> prevCounts) {
        return !prevCounts.containsKey(clusterId);
    }

    /** Truncate to width columns, using an ellipsis when it doesn't fit. */
    static String truncate(String s, int width) {
        if (width <= 0) {
            return "";
        }
        if (s.length() <= width) {
            return s;
        }
        if (width == 1) {
            return "…";
        }
        return s.substring(0, width - 1) + "…";
    }

    /** HH:MM:SS for a running duration. */
    static String elapsed(Duration d) {
        long secs = Math.max(0, d.getSeconds());
        return String.format("%02d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60);
    }

    private static double secondsBetween(Instant from, Instant to) {
        if (from == null || to == null) {
            return 0;
        }
        return Duration.between(from, to).toMillis() / 1000.0;
    }

    /** Clip an attributed line to the terminal width so it never wraps and corrupts the frame. */
    static AttributedString clip(AttributedString line, int cols) {
        return line.columnLength() > cols ? line.columnSubSequence(0, cols) : line;
    }
}
