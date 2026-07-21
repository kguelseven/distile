package org.korhan.distile.demo;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiConsumer;

/**
 * A tiny demo app that drives distile's <b>in-process Log4j2 appender</b>
 * ({@link org.korhan.distile.log4j.DistileAppender}) live — the counterpart to {@link LogSimulator}
 * for the appender frontend.
 *
 * <p>Where {@code LogSimulator} writes fully-rendered log <em>lines</em> to stdout (for the
 * stdin/file path, where masking earns its keep by stripping the timestamp/level/thread prefix),
 * this tool makes real {@code logger.info("... {} ...", args)} calls. The {@code DistileAppender}
 * sees each {@code LogEvent}'s message <em>before serialization</em>: for a parameterized message
 * it takes the format and turns each {@code {}} into the wildcard {@code <*>}, so a login logged
 * 5000 times with different users/IPs collapses to the single template
 * {@code auth user <*> logged in from <*>}. No timestamp/level/logger prefix is ever in the
 * message, which is exactly the in-process appender's advantage: a cleaner signal than a rendered
 * line. (Consequently these templates match feeding the same message <em>strings</em> via stdin —
 * not tailing the app's rendered log file. See the appender's class doc.)
 *
 * <p>The scenario — hot templates plus rare outliers, and the value pools/generators — is reused
 * verbatim from {@link LogSimulator} so both demos show the <em>same</em> recognizable set of
 * events. One template deliberately logs a pre-built (concatenated) string to exercise the
 * appender's non-parameterized fallback path, where masking does all the work — just like the
 * stdin path.
 *
 * <p>Run it via the {@code ./log4jdemo} launcher:
 * <pre>
 *   mvn -q test-compile              # once, so the classes exist
 *   ./log4jdemo --rate 40 --stdout   # watch the report live in the terminal; Ctrl-C to stop
 *   ./log4jdemo --rate 40            # default: report written to distile-templates.log
 * </pre>
 * Output (new-template events, periodic Top-N snapshots, and the final/outlier report on shutdown)
 * comes from the appender itself. It goes to a file by default ({@code log4j2-distile-demo.xml}) or
 * to the console with {@code --stdout} ({@code log4j2-distile-stdout-demo.xml}).
 */
public final class Log4jDemo {

    // The two demo configs. Both are non-default file names so neither auto-loads during the
    // Surefire test run; Log4jDemo selects one explicitly via the log4j2.configurationFile property.
    private static final String CONFIG_FILE   = "log4j2-distile-demo.xml";         // default: report -> file
    private static final String CONFIG_STDOUT = "log4j2-distile-stdout-demo.xml";  // --stdout: report -> console

    // Must match the file= attribute in CONFIG_FILE. Used only for the console hint in file mode.
    private static final String REPORT_FILE = "distile-templates.log";

    /** A named log template: a weight (how often it fires) and the log call it makes. */
    private record Template(int weight, BiConsumer<Logger, Random> emit) {}

    public static void main(String[] args) throws InterruptedException {
        Config cfg = Config.parse(args);
        if (cfg.help) {
            printUsage();
            return;
        }

        // Pick the config and set the property BEFORE the first LogManager call below — that call
        // triggers Log4j2 startup, which reads log4j2.configurationFile exactly once. (This is why
        // the logger is a local, acquired here, not a static field initialized at class load.)
        System.setProperty("log4j2.configurationFile", cfg.stdout ? CONFIG_STDOUT : CONFIG_FILE);
        if (!cfg.stdout) {
            System.out.println("» distilling to " + REPORT_FILE + " — watch it live with:  tail -f " + REPORT_FILE);
            System.out.println("  (add --stdout to print the report to this console instead)");
        }
        Logger log = LogManager.getLogger("app");

        Random rnd = cfg.seed != null ? new Random(cfg.seed) : new Random();
        List<Template> templates = templates();
        int totalWeight = templates.stream().mapToInt(Template::weight).sum();

        long sleepMs = cfg.rate > 0 ? Math.max(0, 1000L / cfg.rate) : 0;
        long emitted = 0;

        // On normal exit (count reached) or Ctrl-C, Log4j2's own shutdown hook stops the appender,
        // which flushes distile's final + outlier report. We do not print anything ourselves — the
        // appender owns all emission, exactly as on the CLI path.
        while (cfg.count == 0 || emitted < cfg.count) {
            pick(templates, totalWeight, rnd).emit().accept(log, rnd);
            emitted++;
            if (sleepMs > 0) {
                Thread.sleep(sleepMs);
            }
        }
    }

    /** Weighted pick: roll in [0,totalWeight) and walk cumulative weights. */
    private static Template pick(List<Template> templates, int totalWeight, Random rnd) {
        int roll = rnd.nextInt(totalWeight);
        int acc = 0;
        for (Template t : templates) {
            acc += t.weight();
            if (roll < acc) {
                return t;
            }
        }
        return templates.get(templates.size() - 1);
    }

    /**
     * The same scenario as {@link LogSimulator}, expressed as parameterized Log4j2 calls. The
     * {@code {}} placeholders become {@code <*>} in the clustered template; the literal words are
     * what distile keys on. Weights mirror LogSimulator so the Top-N ranking lines up.
     */
    private static List<Template> templates() {
        List<Template> t = new ArrayList<>();

        // --- Hot templates (dominate the stream) -----------------------------
        t.add(new Template(120, (log, r) ->
                log.info("auth user {} logged in from {}", LogSimulator.user(r), LogSimulator.ip(r))));
        t.add(new Template(160, (log, r) ->
                log.info("http GET {} {} {}ms", LogSimulator.path(r), LogSimulator.ok(r), LogSimulator.latency(r))));
        t.add(new Template(90, (log, r) ->
                log.info("http POST {} {} {}ms", LogSimulator.path(r), LogSimulator.ok(r), LogSimulator.latency(r))));
        t.add(new Template(140, (log, r) ->
                log.debug("cache {} key k{}", r.nextBoolean() ? "hit" : "miss", r.nextInt(100000))));
        t.add(new Template(70, (log, r) ->
                log.warn("db slow query q{} took {}ms", r.nextInt(500), 200 + r.nextInt(4000))));
        t.add(new Template(80, (log, r) ->
                log.info("worker job {} completed in {}ms", LogSimulator.uuid(r), LogSimulator.latency(r))));
        t.add(new Template(50, (log, r) ->
                log.warn("http upstream {} retry {}/3", LogSimulator.host(r), 1 + r.nextInt(3))));
        t.add(new Template(40, (log, r) ->
                log.error("http request {} failed status {}", LogSimulator.uuid(r), LogSimulator.err(r))));
        t.add(new Template(60, (log, r) ->
                log.debug("metrics cpu {} mem {} load {}", r.nextInt(100), r.nextInt(100), r.nextInt(16))));

        // --- Rare outliers (should surface in distile's outlier view on a bounded --count run) ---
        t.add(new Template(1, (log, r) ->
                log.info("config reloaded from /etc/app/config.yaml checksum {}", LogSimulator.hex(r))));
        t.add(new Template(1, (log, r) ->
                log.error("FATAL disk full on /var/lib/app free {}bytes", r.nextInt(4096))));
        // Non-parameterized on purpose: a pre-built string exercises the appender's SimpleMessage
        // fallback, where masking (not placeholder hints) collapses the variable part.
        t.add(new Template(1, (log, r) ->
                log.warn("clock skew detected " + (50 + r.nextInt(900)) + "ms drift")));

        return t;
    }

    // --- args (mirrors LogSimulator's flags, plus --stdout) ------------------
    private record Config(int rate, long count, Long seed, boolean stdout, boolean help) {
        static Config parse(String[] args) {
            int rate = 20;
            long count = 0;
            Long seed = null;
            boolean stdout = false;
            boolean help = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--rate"   -> rate = Integer.parseInt(args[++i]);
                    case "--count"  -> count = Long.parseLong(args[++i]);
                    case "--seed"   -> seed = Long.parseLong(args[++i]);
                    case "--stdout" -> stdout = true;
                    case "--help", "-h" -> help = true;
                    default -> {
                        System.err.println("Unknown argument: " + args[i]);
                        help = true;
                    }
                }
            }
            return new Config(rate, count, seed, stdout, help);
        }
    }

    private static void printUsage() {
        System.out.println("""
            Log4jDemo — drive distile's in-process Log4j2 appender with fake logging calls.

            Usage: ./log4jdemo [--rate N] [--count N] [--seed N] [--stdout]

              --rate N    log calls per second (default 20; 0 = as fast as possible)
              --count N   stop after N calls (default 0 = run forever; Ctrl-C to stop)
              --seed N    RNG seed for a reproducible run
              --stdout    print the report to the console instead of the default file
              --help      show this help

            Output (new-template events, Top-N snapshots, final/outlier report) is produced by the
            distile appender. By default it is written to a file (log4j2-distile-demo.xml); with
            --stdout it goes to the console (log4j2-distile-stdout-demo.xml).

            Examples:
              mvn -q test-compile && ./log4jdemo --rate 40 --stdout   # watch live in the terminal
              ./log4jdemo --rate 40                                   # -> distile-templates.log
            """);
    }
}
