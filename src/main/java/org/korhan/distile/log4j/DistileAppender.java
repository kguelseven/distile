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
 * A Log4j2 {@link Appender} that distils the running application's log events into templates
 * <b>in-process</b> — adapter.
 *
 * <p>This is a pure frontend over distile's I/O-free core: it is <em>just another source of
 * lines</em>, exactly like stdin or a file tailer. It touches nothing in {@code core/} — it
 * reuses the same {@link DrainTree} + {@link EmissionPolicy} + {@link SnapshotScheduler} +
 * {@link Reporter} wiring the CLI uses, and only swaps where the lines come from.
 *
 * <h2>How an event becomes a line</h2>
 * For a parameterized message ({@code log.info("user {} in from {}", id, ip)}) we take the
 * <em>format</em> and replace each {@code {}} placeholder with the wildcard {@code <*>},
 * producing {@code "user <*> in from <*>"}, then feed that to {@link DrainTree#add(String)}.
 * The pre-placed {@code <*>} tokens survive masking untouched (no mask rule matches
 * {@code <*>}), while any literal tokens in the format (a hardcoded thread name, a level,
 * an embedded timestamp) are still masked exactly as on the stdin path. So the same message
 * clusters to the same template whether it arrives here or as a raw string — one template
 * space, as the core contract requires.
 *
 * <p>We deliberately read only the placeholder <em>positions</em> ({@code getFormat()}), never
 * the argument <em>values</em>: distile counts templates, it does not capture parameter values.
 *
 * <p>Non-parameterized messages (a {@code SimpleMessage}, or a string built by concatenation)
 * carry no structure, so we fall back to the fully formatted text and let masking do all the
 * work — identical to the stdin path.
 *
 * <p><b>Clusters the message, not a rendered log line.</b> The appender sees the message
 * before serialization, so there is no timestamp/level/logger prefix that a {@code PatternLayout}
 * would add to a file. That is the in-process appender's advantage (cleaner signal), and it
 * means its templates match feeding the same <em>message</em> strings via stdin — not tailing
 * the app's fully rendered log file.
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
     * <p>We key off whether the message <em>has parameters</em>, not off a concrete type. A real
     * {@code logger.info("user {} in from {}", id, ip)} call does NOT produce a
     * {@link ParameterizedMessage}: the default garbage-free {@code ReusableMessageFactory} produces
     * a {@code ReusableParameterizedMessage}, which is not a subtype of {@code ParameterizedMessage}.
     * An {@code instanceof ParameterizedMessage} check would miss it and fall back to the rendered
     * text, so low-cardinality arguments (a username, a path) would each spawn their own template
     * instead of collapsing to one. {@link Message#getParameters()} is non-empty for every
     * parameterized message regardless of factory, so it is the reliable signal; for such a message
     * {@code getFormat()} returns the pattern with {@code {}} placeholders, which {@link #hint} then
     * turns into wildcards. Messages with no parameters (a {@code SimpleMessage}, or a string built
     * by concatenation) have nothing structural to read, so we mask the formatted text — the stdin path.
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
     * Replace each {@code {}} placeholder with the wildcard {@code <*>}. Uses the core's own
     * {@link MaskRule#WILDCARD} constant so the hint speaks the core's vocabulary rather than a
     * magic string. Naive by design: an escaped {@code \{}} is rare and treated as a placeholder.
     */
    static String hint(String format) {
        return format == null ? "" : format.replace("{}", MaskRule.WILDCARD);
    }

    private void emitFinal() {
        if (finalized.compareAndSet(false, true)) {
            scheduler.close();
            reporter.emit(EmissionPolicy.buildFinal(tree.snapshotTopN(-1), tree.clusterCount(), outlierMax));
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
     * appender and {@code distile} on the command line behave identically for the same input.
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
