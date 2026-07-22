package org.korhan.distile.demo;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

/**
 * A tiny, dependency-free log generator for exercising distile live.
 *
 * <p>Emits the console output of a busy <b>Spring Boot 3.x</b> web service in the default Logback
 * layout (<ISO-ts> <LEVEL> <PID> --- [<thread>] <logger> : <message>) — Spring MVC, Hibernate
 * SQL, HikariCP, Tomcat startup, and a few app loggers — so the lines look like what a Java developer
 * actually sees. A handful of "hot" templates dominate; a few rare outliers (startup, pool
 * exhaustion) surface in the outlier view on a short --count run. Run via ./logsim
 * (or Java 21 source-launch, no build) and pipe into distile; see --help.
 *
 * <p>Use --depth 9: the fixed 7-token prefix pushes the real message to token 7, so distile
 * needs a deeper tree to separate events by message rather than by (level, thread, logger).
 *
 * <p>Not a test (no @Test methods, off the surefire pattern) — a hand-run tool. Thread names
 * avoid interior spaces (HikariCP's housekeeper is hyphenated) because naive whitespace tokenization
 * would split the space-padded thread field that real Spring Boot logs use.
 */
public final class LogSimulator {

    // Spring Boot 3.x uses an ISO-8601 timestamp with offset (yyyy-MM-dd'T'HH:mm:ss.SSSXXX). Our
    // synthetic clock has no zone, so we render a literal 'Z' (UTC), which is what a containerized
    // app typically logs anyway.
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    // Timestamp source. With --seed we advance a synthetic clock deterministically (fixed base +
    // fixed step per line) so the whole stream — timestamps included — is byte-for-byte reproducible.
    // Without a seed we use live wall-clock time.
    private static final LocalDateTime CLOCK_BASE = LocalDateTime.of(2026, 7, 19, 10, 0, 0);
    private static final long STEP_MILLIS = 37;
    private static boolean deterministicClock;
    private static long lineIndex;
    private static String currentTs = "";

    // The process id: constant for a run (a process has one PID), drawn once so it is deterministic
    // under --seed. Masks to <*> like any number, so it never fragments templates.
    private static int pid;

    // The value pools and the variable-part generators below are package-private (not private)
    // on purpose: the sibling Log4jDemo reuses this exact vocabulary so the two demos exercise the
    // *same* recognizable scenario over different frontends (rendered stdout line here; real
    // parameterized Log4j2 calls there). Single source of truth for the scenario, no duplication.
    static final String[] PATHS =
            {"/api/users", "/api/orders", "/api/carts", "/actuator/health", "/api/search"};
    static final String[] METHODS = {"GET", "POST", "PUT", "DELETE"};
    // HTTP status + reason phrase, chosen so each reason is a single token (stable token count).
    static final String[] COMPLETIONS = {"200 OK", "201 Created", "202 Accepted", "403 Forbidden", "409 Conflict"};

    // Logger names as Spring Boot abbreviates them in the console (%-40.40logger{39}).
    static final String LOG_DISPATCHER = "o.s.web.servlet.DispatcherServlet";
    static final String LOG_HIBERNATE  = "org.hibernate.SQL";
    static final String LOG_HIKARI     = "com.zaxxer.hikari.pool.HikariPool";
    static final String LOG_ORDERS     = "c.e.demo.OrderController";
    static final String LOG_PAYMENTS   = "c.e.demo.PaymentService";
    static final String LOG_REPORT     = "c.e.demo.ReportJob";
    static final String LOG_EXCEPTIONS = "c.e.demo.GlobalExceptionHandler";
    static final String LOG_TOMCAT     = "o.s.b.w.embedded.tomcat.TomcatWebServer";
    static final String LOG_APP        = "c.e.demo.DemoApplication";

    /** A named log template: a weight (how often it fires) and a line generator. */
    private record Template(int weight, Function<Random, String> gen) {}

    public static void main(String[] args) throws InterruptedException {
        Config cfg = Config.parse(args);
        if (cfg.help) {
            printUsage();
            return;
        }

        Random rnd = cfg.seed != null ? new Random(cfg.seed) : new Random();
        deterministicClock = cfg.seed != null;
        pid = 10000 + rnd.nextInt(90000);
        List<Template> templates = templates();
        int totalWeight = templates.stream().mapToInt(Template::weight).sum();

        long sleepMs = cfg.rate > 0 ? Math.max(0, 1000L / cfg.rate) : 0;
        long emitted = 0;

        while (cfg.count == 0 || emitted < cfg.count) {
            lineIndex = emitted;
            currentTs = deterministicClock
                    ? CLOCK_BASE.plusNanos(lineIndex * STEP_MILLIS * 1_000_000L).format(TS)
                    : LocalDateTime.now().format(TS);
            String line = pick(templates, totalWeight, rnd).gen().apply(rnd);
            System.out.println(line);
            System.out.flush();
            if (System.out.checkError()) {
                // Downstream closed the pipe (e.g. distile exited) — stop quietly.
                break;
            }
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

    private static List<Template> templates() {
        List<Template> t = new ArrayList<>();

        // --- Hot templates: a busy Spring Boot 3 web service ------------------
        // Spring MVC request in / request complete (DispatcherServlet at DEBUG).
        t.add(new Template(160, r -> sb("DEBUG", execThread(r), LOG_DISPATCHER,
                method(r) + " \"" + path(r) + "\", parameters={masked}")));
        t.add(new Template(120, r -> sb("DEBUG", execThread(r), LOG_DISPATCHER,
                "Completed " + completion(r))));
        // Hibernate SQL (org.hibernate.SQL at DEBUG) — a fixed query so it clusters cleanly.
        t.add(new Template(140, r -> sb("DEBUG", execThread(r), LOG_HIBERNATE,
                "select o1_0.id,o1_0.total,o1_0.status from orders o1_0 where o1_0.id=?")));
        // HikariCP pool stats (verbatim shape from HikariPool.logPoolState()).
        t.add(new Template(140, r -> {
            int[] s = hikariStats(r);
            return sb("DEBUG", "HikariPool-1-housekeeper", LOG_HIKARI,
                    "HikariPool-1 - Pool stats (total=" + s[0] + ", active=" + s[1]
                            + ", idle=" + s[2] + ", waiting=" + s[3] + ")");
        }));
        // Application loggers.
        t.add(new Template(90, r -> sb("INFO", execThread(r), LOG_ORDERS,
                "Created order " + orderId(r) + " for customer " + customerId(r))));
        t.add(new Template(70, r -> sb("WARN", execThread(r), LOG_PAYMENTS,
                "Retrying payment gateway attempt " + (1 + r.nextInt(3)) + "/3")));
        t.add(new Template(60, r -> sb("INFO", "scheduling-1", LOG_REPORT,
                "Generated daily report in " + latency(r) + "ms")));
        t.add(new Template(40, r -> sb("ERROR", execThread(r), LOG_EXCEPTIONS,
                "Unhandled exception handling request " + uuid(r))));

        // --- Rare outliers (surface in distile's outlier view on a bounded --count run) ----------
        t.add(new Template(1, r -> sb("INFO", "main", LOG_TOMCAT,
                "Tomcat started on port 8080 (http) with context path '/'")));
        t.add(new Template(1, r -> sb("INFO", "main", LOG_APP,
                "Started DemoApplication in 3.456 seconds (process running for 4.123)")));
        t.add(new Template(1, r -> sb("WARN", execThread(r), LOG_HIKARI,
                "HikariPool-1 - Connection is not available, request timed out after 30000ms")));

        return t;
    }

    /**
     * Render one line in the Spring Boot 3.x default console layout:
     * {@code <ts> <LEVEL> <PID> --- [<thread>] <logger> : <message>}. The level is right-justified
     * to width 5 (as {@code %5p} does), hence the leading space on INFO/WARN; the logger is
     * left-justified to 40 (as {@code %-40.40logger{39}} does), hence the run of spaces before the
     * {@code :}. Both are harmless to whitespace tokenization (padding falls between tokens).
     */
    private static String sb(String level, String thread, String logger, String message) {
        return String.format("%s %5s %d --- [%s] %-40s : %s", ts(), level, pid, thread, logger, message);
    }

    // --- variable-part generators --------------------------------------------
    // ts() stays private: the timestamp is part of a *rendered* line, which is this frontend's job.
    // The appender sees the message before serialization, so Log4jDemo has no use for it. Everything
    // below is shared with Log4jDemo (see the pools comment above).
    private static String ts()             { return currentTs; }
    static String path(Random r)           { return PATHS[r.nextInt(PATHS.length)]; }
    static String method(Random r)         { return METHODS[r.nextInt(METHODS.length)]; }
    static String completion(Random r)     { return COMPLETIONS[r.nextInt(COMPLETIONS.length)]; }
    static String execThread(Random r)     { return "http-nio-8080-exec-" + (1 + r.nextInt(8)); }
    static int    latency(Random r)        { return 1 + r.nextInt(800); }
    static int    orderId(Random r)        { return 10000 + r.nextInt(90000); }
    static int    customerId(Random r)     { return 1000 + r.nextInt(9000); }

    /** HikariCP gauge snapshot: {total, active, idle, waiting}, with total fixed at the pool size. */
    static int[] hikariStats(Random r) {
        int active = r.nextInt(11);
        return new int[]{10, active, 10 - active, r.nextInt(4)};
    }

    static String uuid(Random r) {
        return String.format("%08x-%04x-%04x-%04x-%012x",
                r.nextInt(), r.nextInt(0x10000), r.nextInt(0x10000), r.nextInt(0x10000),
                r.nextLong() & 0xFFFFFFFFFFFFL);
    }

    // --- args ----------------------------------------------------------------
    private record Config(int rate, long count, Long seed, boolean help) {
        static Config parse(String[] args) {
            int rate = 20;
            long count = 0;
            Long seed = null;
            boolean help = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--rate"  -> rate = Integer.parseInt(args[++i]);
                    case "--count" -> count = Long.parseLong(args[++i]);
                    case "--seed"  -> seed = Long.parseLong(args[++i]);
                    case "--help", "-h" -> help = true;
                    default -> {
                        System.err.println("Unknown argument: " + args[i]);
                        help = true;
                    }
                }
            }
            return new Config(rate, count, seed, help);
        }
    }

    private static void printUsage() {
        System.out.println("""
            LogSimulator — emit fake Spring Boot 3 console logs to stdout for distile.

            Usage: ./logsim [--rate N] [--count N] [--seed N]
               or: java src/test/java/org/korhan/distile/demo/LogSimulator.java [--rate N] [--count N] [--seed N]

              --rate N    lines per second (default 20; 0 = as fast as possible)
              --count N   stop after N lines (default 0 = run forever)
              --seed N    RNG seed for a reproducible stream
              --help      show this help

            Example:
              ./logsim --rate 40 | ./distile --snapshot-interval 3 --depth 9

            (--depth 9 reaches past Spring Boot's fixed 7-token prefix — timestamp, level, PID, ---,
             thread, logger, ':' — so events separate by their message. A shallower depth groups by
             logger/thread and merges different messages of the same length.)
            """);
    }
}
