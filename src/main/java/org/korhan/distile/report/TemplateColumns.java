package org.korhan.distile.report;

import org.korhan.distile.core.LogCluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Restores the column layout that whitespace tokenizing threw away.
 *
 * A logging framework already pads its own output so the level and logger line up. Tokenizer
 * splits on whitespace and drops that padding, so a table of templates prints ragged. This
 * class puts the columns back.
 *
 * Columns are token positions, and the tokens are the ones already stored in the cluster
 * template. Tokenizer drops empty tokens, so no token contains a space and splitting a
 * template string on a single space returns those tokens exactly. Nothing is parsed again and
 * the raw line is not needed. Alignment only inserts spaces between existing tokens. It never
 * changes, reorders or drops one.
 *
 * There is no configuration, on purpose. A flag would let someone point this at a format it
 * cannot handle. Instead it decides per table, in three tiers:
 *
 * 1. Field grid. The message boundary is the first token ending in a colon, and it has to sit
 *    at the same token index in every row. That agreement between rows is what makes the tier
 *    reliable rather than a guess. It fires on logback, Spring Boot, syslog, journald and the
 *    macOS unified log.
 * 2. Structural prefix. With no usable boundary, align the leading positions where every row
 *    holds the same literal, which also covers a column that is a wildcard everywhere, or
 *    where every value is a log level. A level column is aligned to the right, matching the
 *    five wide level field most layouts use.
 * 3. Decline. Templates print exactly as they did before this class existed.
 *
 * Two properties keep tier 2 safe enough to leave on always. A column that is identical in
 * every row is neutral for width, so padding it inserts nothing, and padding appears only
 * where widths genuinely differ. And a format with no leading structure stops the scan at the
 * first position, which does nothing rather than mangling anything.
 *
 * Formats that fall through to tier 3 untouched include the Go log package, nginx access logs,
 * one JSON object per line, and the log4j2 pattern most projects start from, which places the
 * thread before the level so the thread column, whose value differs in every row, stops tier 2
 * early. The Python default layout is out of reach for a deeper reason: it has no whitespace,
 * so the level, the logger and the first message word arrive fused into one token. That
 * degrades clustering well before any formatting and cannot be repaired here.
 *
 * Widths are remembered per instance and only ever grow, so a live frame does not jitter when
 * the ranking changes and the tables of one run share a layout. That state is one int per
 * column, independent of how many lines or templates were seen.
 *
 * Not safe for concurrent use: each reporter owns one instance and calls it from its
 * synchronized emit.
 */
final class TemplateColumns {

    /**
     * Reliability gates, not tuning knobs. Each one is here so alignment declines instead of
     * guessing, which is why none of them is a flag: for a given log format alignment either
     * works or does nothing, and there is nothing to tune in between.
     */
    private static final int MIN_ROWS = 2;              // one row has nothing to align against
    private static final int MIN_PREFIX_TOKENS = 3;     // guards a bare message like "Failed: x"
    private static final int MAX_PREFIX_TOKENS = 12;    // no framework prefix is longer

    /**
     * Level names, matched ignoring case. Wide on purpose: WARNING is Python, Default and Fault
     * and Activity are the macOS unified log, NOTICE through EMERG are syslog, FINE through
     * CONFIG are java.util.logging. A list covering only Java would align Java logs and quietly
     * do nothing for everyone else.
     */
    private static final Set<String> LEVELS = Set.of(
            "TRACE", "DEBUG", "INFO", "WARN", "WARNING", "ERROR", "FATAL", "SEVERE",
            "CRITICAL", "CRIT", "NOTICE", "ALERT", "EMERG", "EMERGENCY", "PANIC",
            "DEFAULT", "FAULT", "ACTIVITY", "FINE", "FINER", "FINEST", "CONFIG", "VERBOSE");

    /** Column index to the widest value seen so far this run. Grows, never shrinks. */
    private final int[] remembered = new int[MAX_PREFIX_TOKENS];

    /**
     * Renders every row template, aligned into columns where that is reliable, returned in the
     * order the rows were given. The grid budget caps how wide the aligned prefix may become,
     * and a tier whose grid exceeds it is rejected. A caller with a bounded viewport passes a
     * fraction of the terminal width so the message never gets starved.
     */
    List<String> align(List<LogCluster> rows, int gridBudget) {
        List<List<String>> tokens = new ArrayList<>(rows.size());
        for (LogCluster c : rows) {
            tokens.add(c.template());
        }

        int columns = anchoredPrefix(tokens);
        int[] widths = fittedWidths(tokens, columns, gridBudget);
        if (widths == null) {
            columns = structuralPrefix(tokens);
            widths = fittedWidths(tokens, columns, gridBudget);
        }
        if (widths == null) {
            return rawTemplates(rows);
        }

        // Commit only the widths of the tier actually chosen, so a rejected probe cannot inflate
        // the remembered grid.
        System.arraycopy(widths, 0, remembered, 0, columns);

        boolean[] right = new boolean[columns];
        for (int i = 0; i < columns; i++) {
            right[i] = allLevels(tokens, i);
        }

        List<String> out = new ArrayList<>(rows.size());
        for (List<String> row : tokens) {
            out.add(render(row, columns, widths, right));
        }
        return out;
    }

    /**
     * Tier 1: how many tokens run up to and including the message boundary, or zero when there
     * is no boundary every row agrees on.
     */
    private static int anchoredPrefix(List<List<String>> rows) {
        if (rows.size() < MIN_ROWS) {
            return 0;
        }
        int anchor = -1;
        for (List<String> row : rows) {
            int i = firstSeparator(row);
            if (i < 0 || (anchor >= 0 && i != anchor)) {
                return 0;   // no separator, or the rows disagree on where it is
            }
            anchor = i;
        }
        return anchor + 1;
    }

    /**
     * Index of this row message boundary, the first token ending in a colon. It has to fall
     * inside the window where a framework prefix can plausibly live, and leave at least one
     * token after it so alignment never puts a padded column at the end of a line. Negative
     * when the row has no such token.
     */
    private static int firstSeparator(List<String> row) {
        int limit = Math.min(MAX_PREFIX_TOKENS, row.size() - 1);
        for (int i = MIN_PREFIX_TOKENS; i < limit; i++) {
            if (row.get(i).endsWith(":")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Tier 2: how many leading positions every row agrees on structurally, either the same
     * literal, which also covers a column that is a wildcard everywhere, or all log levels.
     * Stops at the first position that is neither.
     */
    private static int structuralPrefix(List<List<String>> rows) {
        if (rows.size() < MIN_ROWS) {
            return 0;
        }
        int i = 0;
        while (i < MAX_PREFIX_TOKENS && hasTokenAfter(rows, i)
                && (identical(rows, i) || allLevels(rows, i))) {
            i++;
        }
        return i;
    }

    /** Every row has to keep a token past column i, so no line can end in padding. */
    private static boolean hasTokenAfter(List<List<String>> rows, int i) {
        for (List<String> row : rows) {
            if (row.size() <= i + 1) {
                return false;
            }
        }
        return true;
    }

    private static boolean identical(List<List<String>> rows, int i) {
        String first = rows.get(0).get(i);
        for (List<String> row : rows) {
            if (!first.equals(row.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean allLevels(List<List<String>> rows, int i) {
        for (List<String> row : rows) {
            if (!LEVELS.contains(row.get(i).toUpperCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Widths for the leading columns, merged with what this run has already shown so columns
     * never shrink. Null when there is nothing to align, or when the grid would not fit the
     * budget.
     */
    private int[] fittedWidths(List<List<String>> rows, int columns, int gridBudget) {
        if (columns <= 0) {
            return null;
        }
        int[] widths = new int[columns];
        int total = 0;
        for (int i = 0; i < columns; i++) {
            int w = remembered[i];
            for (List<String> row : rows) {
                w = Math.max(w, row.get(i).length());
            }
            widths[i] = w;
            total += w + 1;     // plus the space that separates this column from the next
        }
        return total <= gridBudget ? widths : null;
    }

    /** Pads the aligned columns, then appends the remaining tokens as they are. */
    private static String render(List<String> tokens, int columns, int[] widths, boolean[] right) {
        StringBuilder sb = new StringBuilder(128);
        for (int i = 0; i < columns; i++) {
            String token = tokens.get(i);
            int pad = widths[i] - token.length();
            // A level column goes to the right, matching the five wide level field of most layouts.
            if (right[i]) {
                sb.append(" ".repeat(pad)).append(token);
            } else {
                sb.append(token).append(" ".repeat(pad));
            }
            sb.append(' ');
        }
        for (int i = columns; i < tokens.size(); i++) {
            if (i > columns) {
                sb.append(' ');
            }
            sb.append(tokens.get(i));
        }
        return sb.toString();
    }

    private static List<String> rawTemplates(List<LogCluster> rows) {
        List<String> out = new ArrayList<>(rows.size());
        for (LogCluster c : rows) {
            out.add(c.templateString());
        }
        return out;
    }
}
