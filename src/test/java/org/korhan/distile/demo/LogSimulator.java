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
 * <p>Emits realistic, varied single-line logs to stdout continuously so it can be
 * piped straight in:
 * <pre>
 *   java src/test/java/org/korhan/distile/demo/LogSimulator.java --rate 30 | ./distile --snapshot-interval 3 --depth 6
 * </pre>
 * or, more conveniently, via the {@code ./logsim} launcher.
 *
 * <p>Runs via Java 21 source-launch — no build, no dependencies — and also
 * compiles into the test tree with the rest of the project. It has no {@code @Test}
 * methods and does not match the surefire naming pattern, so it is never run as a
 * test; it is a hand-run tool. A handful of "hot" templates dominate the stream
 * (so distile has clear Top-N winners) while a few rare "outlier" templates fire
 * with probability ~1/813 each. Over a short, bounded run ({@code --count} in the
 * low hundreds) these stay at count &le; 2 and land in distile's outlier view; in
 * an unbounded run they slowly accumulate past that (~12 per 10k lines), so use
 * {@code --count} when you want them to read as outliers. Every template is a
 * single, space-tokenizable line with a stable token count, which is what
 * distile's length bucketing wants.
 */
public final class LogSimulator {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    // Timestamp source. With --seed we advance a synthetic clock deterministically
    // (fixed base + fixed step per line) so the whole stream — timestamps included
    // — is byte-for-byte reproducible. Without a seed we use live wall-clock time.
    private static final LocalDateTime CLOCK_BASE = LocalDateTime.of(2026, 7, 19, 10, 0, 0);
    private static final long STEP_MILLIS = 37;
    private static boolean deterministicClock;
    private static long lineIndex;
    private static String currentTs = "";

    // The value pools and the variable-part generators below are package-private (not private)
    // on purpose: the sibling Log4jDemo reuses this exact vocabulary so the two demos exercise the
    // *same* recognizable scenario over different frontends (rendered stdout line here; real
    // parameterized Log4j2 calls there). Single source of truth for the scenario, no duplication.
    static final String[] THREADS =
            {"main", "http-1", "http-2", "worker-1", "worker-2", "sched-1"};
    static final String[] USERS =
            {"alice", "bob", "carol", "dave", "erin", "frank", "grace", "heidi"};
    static final String[] PATHS =
            {"/api/users", "/api/orders", "/api/cart", "/health", "/login", "/api/search"};
    static final String[] HOSTS =
            {"cache-a", "cache-b", "db-primary", "db-replica", "payments-svc"};
    static final int[] OK_STATUS = {200, 201, 204, 304};
    static final int[] ERR_STATUS = {400, 401, 403, 404, 429, 500, 502, 503};

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

        // --- Hot templates (dominate the stream) -----------------------------
        t.add(new Template(120, r ->
                ts() + " INFO  " + thread(r) + " auth user " + user(r) + " logged in from " + ip(r)));
        t.add(new Template(160, r ->
                ts() + " INFO  " + thread(r) + " http GET " + path(r) + " " + ok(r) + " " + latency(r) + "ms"));
        t.add(new Template(90, r ->
                ts() + " INFO  " + thread(r) + " http POST " + path(r) + " " + ok(r) + " " + latency(r) + "ms"));
        t.add(new Template(140, r ->
                ts() + " DEBUG " + thread(r) + " cache " + (r.nextBoolean() ? "hit" : "miss") + " key k" + r.nextInt(100000)));
        t.add(new Template(70, r ->
                ts() + " WARN  " + thread(r) + " db slow query q" + r.nextInt(500) + " took " + (200 + r.nextInt(4000)) + "ms"));
        t.add(new Template(80, r ->
                ts() + " INFO  " + thread(r) + " worker job " + uuid(r) + " completed in " + latency(r) + "ms"));
        t.add(new Template(50, r ->
                ts() + " WARN  " + thread(r) + " http upstream " + host(r) + " retry " + (1 + r.nextInt(3)) + "/3"));
        t.add(new Template(40, r ->
                ts() + " ERROR " + thread(r) + " http request " + uuid(r) + " failed status " + err(r)));
        t.add(new Template(60, r ->
                ts() + " DEBUG " + thread(r) + " metrics cpu " + r.nextInt(100) + " mem " + r.nextInt(100) + " load " + r.nextInt(16)));

        // --- Rare outliers (should surface in distile's outlier view) --------
        t.add(new Template(1, r ->
                ts() + " INFO  main config reloaded from /etc/app/config.yaml checksum " + hex(r)));
        t.add(new Template(1, r ->
                ts() + " ERROR " + thread(r) + " FATAL disk full on /var/lib/app free " + r.nextInt(4096) + "bytes"));
        t.add(new Template(1, r ->
                ts() + " WARN  " + thread(r) + " clock skew detected " + (50 + r.nextInt(900)) + "ms drift"));

        return t;
    }

    // --- variable-part generators --------------------------------------------
    // ts()/thread() stay private: a timestamp and a bracketed thread name are part of a *rendered*
    // log line, which is this frontend's job. The appender sees the message before serialization,
    // so Log4jDemo has no use for them. Everything below is shared (see the pools comment above).
    private static String ts()            { return currentTs; }
    private static String thread(Random r){ return "[" + THREADS[r.nextInt(THREADS.length)] + "]"; }
    static String user(Random r)  { return USERS[r.nextInt(USERS.length)]; }
    static String path(Random r)  { return PATHS[r.nextInt(PATHS.length)]; }
    static String host(Random r)  { return HOSTS[r.nextInt(HOSTS.length)]; }
    static int    ok(Random r)    { return OK_STATUS[r.nextInt(OK_STATUS.length)]; }
    static int    err(Random r)   { return ERR_STATUS[r.nextInt(ERR_STATUS.length)]; }
    static int    latency(Random r){ return 1 + r.nextInt(500); }
    static String ip(Random r)    { return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + (1 + r.nextInt(254)); }

    static String uuid(Random r) {
        return String.format("%08x-%04x-%04x-%04x-%012x",
                r.nextInt(), r.nextInt(0x10000), r.nextInt(0x10000), r.nextInt(0x10000),
                r.nextLong() & 0xFFFFFFFFFFFFL);
    }

    static String hex(Random r) {
        return String.format("%016x", r.nextLong());
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
            LogSimulator — emit varied fake logs to stdout for distile.

            Usage: ./logsim [--rate N] [--count N] [--seed N]
               or: java src/test/java/org/korhan/distile/demo/LogSimulator.java [--rate N] [--count N] [--seed N]

              --rate N    lines per second (default 20; 0 = as fast as possible)
              --count N   stop after N lines (default 0 = run forever)
              --seed N    RNG seed for a reproducible stream
              --help      show this help

            Example:
              ./logsim --rate 40 | ./distile --snapshot-interval 3 --depth 6

            (--depth 6 keeps the generator's [thread] and request-verb tokens as distinct
             templates; the default --depth 4 merges verbs like GET/POST into one template.)
            """);
    }
}
