package org.korhan.distile.cli;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Folds a Java stack trace spread over many physical lines into ONE record before it
 * reaches the core. Unjoined, every frame is its own template: one exception logged twice
 * produced eight templates, its frames outranked genuine hot templates in the Top-N, and
 * one error incremented counts thirty times — the noise distile exists to remove.
 *
 * <p>It detects continuations, never record starts. Start detection (a leading timestamp,
 * say) is format-dependent: a log without one would make every line look like a
 * continuation and collapse the stream into a single template. Continuation detection
 * fails the other way — an unrecognised line is just its own record, as before.
 *
 * <p>Reliable because it is Java-specific: FRAME, CAUSE, SUPPRESSED and ELISION below come
 * from Throwable.printStackTrace itself, so every framework reproduces them verbatim. A
 * general multiline joiner needs configuration; this one does not.
 *
 * <p>The un-indented exception header is the one ambiguous form — it may be an application
 * logging e.toString() — so it is held tentative and joined only once the next line turns
 * out to be a frame. Buffer depth two. Deliberately NOT a rule: "any indented line is a
 * continuation", the standard log-shipper advice and the only rule here with real false
 * positives, since pretty-printed JSON, XML and SQL are indented too.
 *
 * <p>Only framesKept continuation lines are joined after the header; the rest are dropped,
 * not buffered. Keeping the whole trace is self-defeating — frame counts vary between
 * occurrences of the same error, so the joined lines differ in token count, land in
 * different level-1 buckets and never merge, which is the problem this solves. One frame
 * keeps the throw site at a fixed token count.
 *
 * <p>Cost per line is a few character comparisons: regexes run only behind a cheap prefix
 * gate, the technique Masker uses. Memory is two line references plus one reused
 * StringBuilder. Not thread-safe: called only from the ingest thread.
 */
public final class StackTraceJoiner implements LineSink {

    /**
     * The tokenizer splits on whitespace, so this becomes one stable literal token: it
     * survives into the template and marks where the trace begins, instead of silently
     * running the lines together.
     */
    static final String SEPARATOR = " | ";

    /**
     * Backstop however large framesKept is set, so a file of nothing but frames cannot
     * build a record that is expensive to tokenize.
     */
    static final int MAX_JOINED_LINES = 64;

    // Anchored at line start, so a mid-line "at" in prose cannot match.
    // No trailing '$': Spring Boot's %wEx converter appends per-frame jar data
    // ("... (Svc.java:42) ~[classes/:na]"), and an end anchor would reject every
    // Spring Boot frame — the easiest way to get this rule wrong.
    private static final Pattern FRAME = Pattern.compile("^[ \t]*at\\s+\\S+\\(.*\\)");
    private static final Pattern CAUSE = Pattern.compile("^[ \t]*Caused by:\\s");
    private static final Pattern SUPPRESSED = Pattern.compile("^[ \t]*Suppressed:\\s");
    private static final Pattern ELISION =
            Pattern.compile("^[ \t]*\\.\\.\\.\\s+\\d+\\s+(?:more|common frames omitted)\\b");
    // Fully-qualified (at least one dot) and Throwable-ish. Held tentative — see class doc.
    private static final Pattern EXC_HEADER =
            Pattern.compile("^[ \t]*(?:[\\w$]+\\.)+[\\w$]*(?:Exception|Error|Throwable)(?::.*)?$");

    private final LineSink downstream;
    private final int framesKept;

    // Reused across lines: this runs per input line, so allocating matchers here would be
    // an allocation storm on the hot path.
    private final Matcher frame = FRAME.matcher("");
    private final Matcher cause = CAUSE.matcher("");
    private final Matcher suppressed = SUPPRESSED.matcher("");
    private final Matcher elision = ELISION.matcher("");
    private final Matcher excHeader = EXC_HEADER.matcher("");

    // The record under construction is EITHER `head` (nothing joined yet) or `joined`
    // (at least one continuation appended) — never both. Splitting it this way keeps the
    // overwhelmingly common case, a record that is just one line, allocation-free.
    private String head;
    private StringBuilder joined;
    private boolean joinedActive;
    private int appended;

    // An exception header awaiting confirmation from the next line.
    private String tentative;

    /**
     * @param downstream where completed records go
     * @param framesKept continuation lines joined after the exception header; 0 keeps the
     *                   exception but no frame. Capped at MAX_JOINED_LINES.
     */
    public StackTraceJoiner(LineSink downstream, int framesKept) {
        this.downstream = downstream;
        this.framesKept = Math.min(Math.max(framesKept, 0), MAX_JOINED_LINES);
    }

    @Override
    public void accept(String line) {
        if (tentative != null) {
            String held = tentative;
            tentative = null;
            if (isFrame(line, firstNonWs(line))) {
                // Confirmed: header and frame both belong to the pending record. The
                // header joins for free; only the frame is charged to the budget.
                append(held);
                appendWithinBudget(line);
                return;
            }
            // Not a trace after all — the held line was a record in its own right.
            emitRecord();
            startRecord(held);
            // Fall through: `line` still has to be classified against that new record.
        }

        if (hasRecord()) {
            int i = firstNonWs(line);
            if (isContinuation(line, i)) {
                appendWithinBudget(line);
                return;
            }
            if (isExcHeader(line, i)) {
                tentative = line;
                return;
            }
        }

        emitRecord();
        startRecord(line);
    }

    @Override
    public boolean hasPending() {
        return hasRecord() || tentative != null;
    }

    @Override
    public void onIdle() {
        flush();
    }

    @Override
    public void onEnd() {
        flush();
        downstream.onEnd();
    }

    /** Release the record under construction and any unconfirmed header, in order. */
    private void flush() {
        emitRecord();
        if (tentative != null) {
            downstream.accept(tentative);
            tentative = null;
        }
    }

    private boolean hasRecord() {
        return head != null || joinedActive;
    }

    private void startRecord(String line) {
        head = line;
        joinedActive = false;
        appended = 0;
    }

    private void emitRecord() {
        if (joinedActive) {
            downstream.accept(joined.toString());
        } else if (head != null) {
            downstream.accept(head);
        }
        head = null;
        joinedActive = false;
        appended = 0;
    }

    /** Join a continuation line, or drop it once the extent budget is spent. */
    private void appendWithinBudget(String line) {
        if (appended < framesKept) {
            append(line);
            appended++;
        }
        // Over budget: dropped outright. Not buffered, so a long trace costs nothing.
    }

    private void append(String line) {
        if (!joinedActive) {
            if (joined == null) {
                joined = new StringBuilder();
            }
            // Allocated only when a trace actually shows up — never per input line.
            joined.setLength(0);
            joined.append(head);
            head = null;
            joinedActive = true;
        }
        joined.append(SEPARATOR).append(line.strip());
    }

    // --- detection -------------------------------------------------------------------
    // Every check is gated on a cheap prefix test first; the regex runs only when the
    // gate passes. A normal log line fails all five gates on its first character.

    private boolean isContinuation(String line, int i) {
        return isFrame(line, i)
                || (line.startsWith("Caused by:", i) && cause.reset(line).find())
                || (line.startsWith("Suppressed:", i) && suppressed.reset(line).find())
                || (line.startsWith("...", i) && elision.reset(line).find());
    }

    private boolean isFrame(String line, int i) {
        // "at" followed by whitespace — the regex allows a tab there, so the gate must too.
        return line.startsWith("at", i)
                && i + 2 < line.length()
                && isBlank(line.charAt(i + 2))
                && frame.reset(line).find();
    }

    private boolean isExcHeader(String line, int i) {
        // No prefix to gate on, so gate on what the pattern requires instead: a
        // non-digit start (which rejects every timestamp-led line outright) and one of
        // the three Throwable suffixes somewhere in the line.
        if (i >= line.length()) {
            return false;
        }
        char c = line.charAt(i);
        if (c >= '0' && c <= '9') {
            return false;
        }
        if (line.indexOf("Exception") < 0 && line.indexOf("Error") < 0 && line.indexOf("Throwable") < 0) {
            return false;
        }
        return excHeader.reset(line).find();
    }

    private static int firstNonWs(String line) {
        int i = 0;
        while (i < line.length() && isBlank(line.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t';
    }
}
