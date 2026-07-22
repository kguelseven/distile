package org.korhan.distile.log4j;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.korhan.distile.core.DrainConfig;
import org.korhan.distile.core.DrainTree;
import org.korhan.distile.core.MaskRule;
import org.korhan.distile.core.Masker;
import org.korhan.distile.emission.EmissionPolicy;
import org.korhan.distile.emission.SnapshotScheduler;
import org.korhan.distile.report.JsonReporter;
import org.korhan.distile.report.Reporter;
import org.korhan.distile.report.TextReporter;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A Log4j2 Appender that distils the running application's log events into
 * templates in-process.
 *
 * <p>It is just another source of lines over distile's I/O-free core — like stdin
 * or a file tailer — reusing the same DrainTree + EmissionPolicy +
 * SnapshotScheduler + Reporter wiring as the CLI, and only changing
 * where lines come from. It touches nothing in core/.
 *
 * <p>For a parameterized message (log.info("user {} in from {}", id, ip)) it
 * takes the format and replaces each {} with the wildcard <*>,
 * feeding "user <*> in from <*>" to DrainTree#add(String). Those
 * <*> tokens pass through masking untouched while literal tokens are masked
 * as on the stdin path, so a message clusters to the same template however it
 * arrives. Only placeholder positions are read (getFormat()), never
 * argument values — distile counts templates, it does not capture parameter values.
 * A non-parameterized message (SimpleMessage, or a concatenated string) has
 * no structure, so it falls back to the fully formatted text and lets masking do the
 * work, exactly like stdin.
 *
 * <p>It clusters the message, not a rendered log line: the appender sees
 * before serialization, with no timestamp/level/logger prefix a PatternLayout
 * would prepend. That is its advantage — cleaner signal — and it means i
 * match feeding the same messages via stdin, not tailing the rendered log file.
 */
@Plugin(name = "Distile", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE, printObject = true)
public final class DistileAppender extends AbstractAppender {

    private final DrainTree tree;
    private final EmissionPolicy policy;
    private final SnapshotScheduler scheduler;
    private final Reporter reporter;
    private final PrintStream out;
    private final boolean ownsOut;   // true only when we opened a file and must close it
    private final long outlierMax;

    // The final report must fire exactly once, even if stop() is called more than once.
    private final AtomicBoolean finalized = new AtomicBoolean(false);

    private DistileAppender(String name, Filter filter, DrainTree tree, EmissionPolicy policy,
                            SnapshotScheduler scheduler, Reporter reporter, PrintStream out,
                            boolean ownsOut, long outlierMax) {
        // No layout: we consume the LogEvent's message directly and never serialize it.
        super(name, filter, null, /*ignoreExceptions=*/true, Property.EMPTY_ARRAY);
        this.tree = tree;
        this.policy = policy;
        this.scheduler = scheduler;
        this.reporter = reporter;
        this.out = out;
        this.ownsOut = ownsOut;
        this.outlierMax = outlierMax;
    }

    @Override
    public void start() {
        super.start();
        scheduler.start();
    }

    @Override
    public boolean stop(long timeout, TimeUnit timeUnit) {
        setStopping();
        emitFinal();
        boolean stopped = super.stop(timeout, timeUnit);
        setStopped();
        return stopped;
    }

    @Override
    public void append(LogEvent event) {
        try {
            policy.onMatch(tree.add(lineFor(event.getMessage())));
        } catch (RuntimeException e) {
            // Distile must never disrupt the host application's own logging. Route the failure
            // through Log4j's appender error handling instead of propagating.
            error("distile appender failed to process a log event", event, e);
        }
    }

    /**
     * Turn a Log4j2 message into the single line distile should cluster.
     *
     * <p>We detect a parameterized message by whether it has parameters, not by
     * its type. A real logger.info("user {} in from {}", id, ip) does not produce
     * a ParameterizedMessage — the default garbage-free factory produces a
     * ReusableParameterizedMessage, which is not a subtype — so an
     * instanceof check would miss it, fall back to rendered text, and let each
     * distinct argument spawn its own template. Message#getParameters() is
     * non-empty for any parameterized message regardless of factory, so it is the
     * reliable signal; getFormat() then gives the pattern with {}
     * placeholders for #hint to turn into wildcards. A message with no parameters
     * (a SimpleMessage, or a concatenated string) has nothing structural to read,
     * so we mask the formatted text — the stdin path.
     */
    @SuppressWarnings("deprecation") // Message.getFormat(): the pattern is exactly what we want here.
    static String lineFor(Message message) {
        Object[] params = message.getParameters();
        if (params != null && params.length > 0) {
            return hint(message.getFormat());
        }
        return message.getFormattedMessage();
    }

    /**
     * Replace each {} placeholder with the wildcard <*>. Uses the core's own
     * MaskRule#WILDCARD constant so the hint speaks the core's vocabulary rather than a
     * magic string. Naive by design: an escaped \{} is rare and treated as a placeholder.
     */
    static String hint(String format) {
        return format == null ? "" : format.replace("{}", MaskRule.WILDCARD);
    }

    private void emitFinal() {
        if (finalized.compareAndSet(false, true)) {
            scheduler.close();
            reporter.emit(EmissionPolicy.buildFinal(tree.snapshotAll(), tree.clusterCount(), outlierMax));
            reporter.flush();
            if (ownsOut) {
                out.close();
            }
        }
    }

    /** Package-private read access to the underlying tree, for tests. */
    DrainTree tree() {
        return tree;
    }

    /**
     * Log4j2 plugin factory. Attributes mirror the CLI flags with the same defaults, so an
     * appender and distile on the command line behave identically for the same input.
     */
    @PluginFactory
    public static DistileAppender createAppender(
            @PluginAttribute("name") String name,
            @PluginElement("Filter") Filter filter,
            @PluginAttribute(value = "simThreshold", defaultDouble = DrainConfig.DEFAULT_SIM_THRESHOLD) double simThreshold,
            @PluginAttribute(value = "depth", defaultInt = DrainConfig.DEFAULT_DEPTH) int depth,
            @PluginAttribute(value = "maxChildren", defaultInt = DrainConfig.DEFAULT_MAX_CHILDREN) int maxChildren,
            @PluginAttribute(value = "topN", defaultInt = 10) int topN,
            @PluginAttribute(value = "snapshotInterval", defaultLong = 5) long snapshotInterval,
            @PluginAttribute(value = "emitNew", defaultBoolean = true) boolean emitNew,
            @PluginAttribute(value = "json", defaultBoolean = false) boolean json,
            @PluginAttribute(value = "outlierMax", defaultLong = 2) long outlierMax,
            @PluginAttribute("file") String file) {

        if (name == null) {
            LOGGER.error("DistileAppender requires a 'name' attribute");
            return null;
        }

        boolean ownsOut = file != null;
        PrintStream out;
        if (ownsOut) {
            try {
                out = new PrintStream(new FileOutputStream(file), /*autoFlush=*/true, StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.error("DistileAppender could not open output file '{}'", file, e);
                return null;
            }
        } else {
            out = System.out;
        }

        Masker masker = Masker.withDefaults();
        DrainConfig config = new DrainConfig(depth, maxChildren, simThreshold);
        DrainTree tree = new DrainTree(config, masker);

        Reporter reporter = json ? new JsonReporter(out) : new TextReporter(out);
        EmissionPolicy policy = EmissionPolicy.of(emitNew, new long[0], reporter);
        SnapshotScheduler scheduler = new SnapshotScheduler(tree, reporter, topN, snapshotInterval);

        return new DistileAppender(name, filter, tree, policy, scheduler, reporter, out, ownsOut, outlierMax);
    }
}
