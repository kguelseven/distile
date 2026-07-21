package org.korhan.distile.demo;

import org.apache.logging.log4j.LogManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Drives distile's in-process Log4j2 appender ({@link org.korhan.distile.log4j.DistileAppender})
 * live — the appender-frontend counterpart to {@link LogSimulator}.
 *
 * <p>Instead of writing rendered lines, it makes real {@code logger.debug("... {} ...", args)} calls
 * over the same Spring Boot 3 scenario as {@code LogSimulator} (reusing its pools/generators, through
 * real framework logger names). The appender reads each message before serialization — no
 * timestamp/level/logger prefix — so parameterized {@code {}} positions become {@code <*>} directly.
 * The HikariCP call reproduces that library's real SLF4J format; the Hibernate SQL line is a pre-built
 * string, exercising the appender's non-parameterized (masking) fallback.
 *
 * <p>Run via {@code ./log4jdemo} (after {@code mvn -q test-compile}): {@code --stdout} prints the
 * report to the console, otherwise it goes to {@code distile-templates.log}. See {@code --help}.
 */
public final class Log4jDemo {

    // The two demo configs. Both are non-default file names so neither auto-loads during the
    // Surefire test run; Log4jDemo selects one explicitly via the log4j2.configurationFile property.
    private static final String CONFIG_FILE   = "log4j2-distile-demo.xml";         // default: report -> file
    private static final String CONFIG_STDOUT = "log4j2-distile-stdout-demo.xml";  // --stdout: report -> console

    // Must match the file= attribute in CONFIG_FILE. Used only for the console hint in file mode.
    private static final String REPORT_FILE = "distile-templates.log";

    // Full framework logger names (what a real app registers; the console abbreviates them). These
    // are plain string constants, so referencing them does NOT trigger Log4j2 startup — loggers are
    // fetched lazily inside the templates, after main() has selected the config.
    private static final String LOG_DISPATCHER = "org.springframework.web.servlet.DispatcherServlet";
    private static final String LOG_HIBERNATE  = "org.hibernate.SQL";
    private static final String LOG_HIKARI     = "com.zaxxer.hikari.pool.HikariPool";
    private static final String LOG_ORDERS     = "com.example.demo.OrderController";
    private static final String LOG_PAYMENTS   = "com.example.demo.PaymentService";
    private static final String LOG_REPORT     = "com.example.demo.ReportJob";
    private static final String LOG_EXCEPTIONS = "com.example.demo.GlobalExceptionHandler";
    private static final String LOG_TOMCAT     = "org.springframework.boot.web.embedded.tomcat.TomcatWebServer";
    private static final String LOG_APP        = "com.example.demo.DemoApplication";

    /** A named log template: a weight (how often it fires) and the log call it makes. */
    private record Template(int weight, Consumer<Random> emit) {}

    public static void main(String[] args) throws InterruptedException {
        Config cfg = Config.parse(args);
        if (cfg.help) {
            printUsage();
            return;
        }

        // Pick the config and set the property BEFORE the first LogManager call below — that call
        // triggers Log4j2 startup, which reads log4j2.configurationFile exactly once. (This is why
        // loggers are fetched lazily inside the templates, never as static fields at class load.)
        System.setProperty("log4j2.configurationFile", cfg.stdout ? CONFIG_STDOUT : CONFIG_FILE);
        if (!cfg.stdout) {
            System.out.println("» distilling to " + REPORT_FILE + " — watch it live with:  tail -f " + REPORT_FILE);
            System.out.println("  (add --stdout to print the report to this console instead)");
        }

        Random rnd = cfg.seed != null ? new Random(cfg.seed) : new Random();
        List<Template> templates = templates();
        int totalWeight = templates.stream().mapToInt(Template::weight).sum();

        long sleepMs = cfg.rate > 0 ? Math.max(0, 1000L / cfg.rate) : 0;
        long emitted = 0;

        // On normal exit (count reached) or Ctrl-C, Log4j2's own shutdown hook stops the appender,
        // which flushes distile's final + outlier report. We do not print anything ourselves — the
        // appender owns all emission, exactly as on the CLI path.
        while (cfg.count == 0 || emitted < cfg.count) {
            pick(templates, totalWeight, rnd).emit().accept(rnd);
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
     * The same Spring Boot 3 scenario as {@link LogSimulator}, expressed as real Log4j2 calls. The
     * {@code {}} placeholders become {@code <*>} in the clustered template; the literal words are
     * what distile keys on. Weights mirror LogSimulator so the Top-N ranking lines up.
     */
    private static List<Template> templates() {
        List<Template> t = new ArrayList<>();

        // --- Hot templates: a busy Spring Boot 3 web service ------------------
        // Spring MVC request in / request complete (DispatcherServlet at DEBUG). "parameters={masked}"
        // is a literal (Spring masks params by default), not a placeholder — hint() leaves it intact.
        t.add(new Template(160, r -> LogManager.getLogger(LOG_DISPATCHER)
                .debug("{} \"{}\", parameters={masked}", LogSimulator.method(r), LogSimulator.path(r))));
        t.add(new Template(120, r -> LogManager.getLogger(LOG_DISPATCHER)
                .debug("Completed {}", LogSimulator.completion(r))));
        // Hibernate logs SQL as a pre-built string (no placeholders) -> appender's SimpleMessage
        // fallback -> masking. A fixed query so it clusters cleanly.
        t.add(new Template(140, r -> LogManager.getLogger(LOG_HIBERNATE)
                .debug("select o1_0.id,o1_0.total,o1_0.status from orders o1_0 where o1_0.id=?")));
        // HikariCP pool stats — the library's actual SLF4J format string.
        t.add(new Template(140, r -> {
            int[] s = LogSimulator.hikariStats(r);
            LogManager.getLogger(LOG_HIKARI)
                    .debug("HikariPool-1 - Pool stats (total={}, active={}, idle={}, waiting={})",
                            s[0], s[1], s[2], s[3]);
        }));
        // Application loggers.
        t.add(new Template(90, r -> LogManager.getLogger(LOG_ORDERS)
                .info("Created order {} for customer {}", LogSimulator.orderId(r), LogSimulator.customerId(r))));
        t.add(new Template(70, r -> LogManager.getLogger(LOG_PAYMENTS)
                .warn("Retrying payment gateway attempt {}/3", 1 + r.nextInt(3))));
        t.add(new Template(60, r -> LogManager.getLogger(LOG_REPORT)
                .info("Generated daily report in {}ms", LogSimulator.latency(r))));
        t.add(new Template(40, r -> LogManager.getLogger(LOG_EXCEPTIONS)
                .error("Unhandled exception handling request {}", LogSimulator.uuid(r))));

        // --- Rare outliers (surface in distile's outlier view on a bounded --count run) ----------
        t.add(new Template(1, r -> LogManager.getLogger(LOG_TOMCAT)
                .info("Tomcat started on port {} (http) with context path '{}'", 8080, "/")));
        t.add(new Template(1, r -> LogManager.getLogger(LOG_APP)
                .info("Started DemoApplication in {} seconds (process running for {})", "3.456", "4.123")));
        t.add(new Template(1, r -> LogManager.getLogger(LOG_HIKARI)
                .warn("HikariPool-1 - Connection is not available, request timed out after {}ms", 30000)));

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
            Log4jDemo — drive distile's in-process Log4j2 appender with fake Spring Boot 3 logging.

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
