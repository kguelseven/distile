# <img src="distile-icon.svg" alt="" height="48" valign="middle"> distile

**See what your logs are actually saying, without reading every line.**

When an app runs, it writes thousands of log lines, mostly the same
messages over and over, just with different values:

    2026-07-19T10:00:00.100Z  INFO 24236 --- [http-nio-8080-exec-3] c.e.demo.OrderController : Created order 33712 for customer 7708
    2026-07-19T10:00:00.137Z ERROR 24236 --- [http-nio-8080-exec-8] c.e.demo.GlobalExceptionHandler : Unhandled exception handling request 5ad9d4ac-5a59-5c50-f7cd-524471cef2da
    2026-07-19T10:00:00.174Z DEBUG 24236 --- [HikariPool-1-housekeeper] com.zaxxer.hikari.pool.HikariPool : HikariPool-1 - Pool stats (total=10, active=7, idle=3, waiting=2)
    2026-07-19T10:00:00.211Z  INFO 24236 --- [http-nio-8080-exec-7] c.e.demo.OrderController : Created order 99662 for customer 9961
    2026-07-19T10:00:00.248Z ERROR 24236 --- [http-nio-8080-exec-8] c.e.demo.GlobalExceptionHandler : Unhandled exception handling request 41ad07fd-8555-5715-2566-e91d4157082a
    2026-07-19T10:00:00.285Z ERROR 24236 --- [http-nio-8080-exec-4] c.e.demo.GlobalExceptionHandler : Unhandled exception handling request c1bbbd6e-d6f9-223e-1001-1a26077d6f97

On a console scrolling by thousands of lines, the messages
that matter drown in the noise. distile groups lines of the same shape
into one **template** (the fixed part of the message) with the changing
parts replaced by `<*>` and counts each one:

    [SNAPSHOT  2026-07-19T10:02:14.318]  top 10 of 14 templates
    1019  #2  <*> DEBUG <*> --- [HikariPool-<*>-housekeeper] com.zaxxer.hikari.pool.HikariPool : HikariPool-<*> - Pool stats (total=<*>, active=<*>, idle=<*>, waiting=<*>)
     616  #3  <*> INFO <*> --- [http-nio-<*>-exec-<*>] c.e.demo.OrderController : Created order <*> for customer <*>
     288  #10  <*> ERROR <*> --- [http-nio-<*>-exec-<*>] c.e.demo.GlobalExceptionHandler : Unhandled exception handling request <*>

So instead of hundreds of near-identical lines, you see the handful of
things your app is really doing, each with a count, and the rare error
hiding among them finally stands out.

It runs locally on a live stream:

    tail -f app.log | ./distile

Under the hood, distile is a from-scratch Java implementation of the
[Drain](https://ieeexplore.ieee.org/document/8029742) algorithm — a streaming log-template extractor. Everything stays on your machine and in
memory (just the templates and their counts).

## Quick start

Requires **Java 21** and **Maven**.

```bash
mvn package                       # builds target/distile.jar
tail -f app.log | ./distile       # or: ./distile -f app.log
```

## Usage

```
distile [FILE] [options]

  -f, --file <path>          read a file (or pass it positionally); default: stdin
      --tail                 keep reading appended lines (like tail -f)
      --json                 emit JSONL instead of text
      --top                  live full-screen top-like view (refreshes in place)
      --top-n <n>            templates shown per snapshot        (default 10)
      --snapshot-interval <s> seconds between Top-N snapshots; 0 = off (default 5, or 2 with --top)
      --no-emit-new          don't print an event on each new template
      --milestones [set]     emit on count milestones; no value = 1,10,100,…
      --outlier-max <n>      count <= n counts as an outlier      (default 2)
      --sim-threshold <0..1> similarity needed to join a cluster  (default 0.5)
      --depth <n>            parse-tree depth                     (default 4)
      --max-children <n>     max node fan-out before <*> overflow (default 100)
      --masks-file <path>    custom mask rules (replaces defaults)
  -h, --help / -V, --version
```

**Emission** runs two layers by default: a `[NEW]` event when a template first
appears, and a Top-N snapshot every 5s. On stream end (or Ctrl-C) it prints the
full ranked list plus outliers.

**`--top`** replaces the scrolling output with a live, full-screen `top`-like view
that refreshes in place (every 2s by default): a header bar (clock, running time,
lines + throughput, template count + new-this-frame) above the ranked table, with
rows that are new or growing highlighted. Works over a pipe
(`tail -f app.log | distile --top`) or a file (`distile --top --tail app.log`);
Ctrl-C exits. When output is not an interactive
terminal (e.g. redirected to a file) it falls back to plain text. Log rotation
following is not yet handled.

## How it works

Every line runs the same near-O(1) pipeline: **tokenize → mask → tree descent →
match → count++**.

- Mask first: Variable tokens (timestamps, IPs, UUIDs, hex, numbers) are
  replaced with `<*>` *before* the tree. This is what stops each unique
  timestamp spawning its own branch.
- Fixed-depth tree: Lines are bucketed by token count, then by their leading
  tokens, down to a leaf holding a handful of candidate templates. Matching only
  ever compares within one leaf, never globally.
- Merge to wildcard: A matching line generalises any disagreeing position to
  `<*>`; templates only lose specificity. Memory is bounded by template count,
  not line count.

> **Note:** variables in *leading* (tree-routing) tokens split into separate
> templates unless masked, an inherent Drain trait. Try `--depth 3` to merge
> more aggressively.

## Try it

A built-in generator emits fake **Spring Boot 3** console logs: Spring MVC, Hibernate,
HikariCP, Tomcat to exercise distile live:

```bash
./logsim --rate 40 | ./distile --snapshot-interval 3 --depth 9
```

Raw lines look like a real app's console:

```
2026-07-19T10:00:00.037Z DEBUG 24236 --- [http-nio-8080-exec-3] o.s.web.servlet.DispatcherServlet        : Completed 409 Conflict
2026-07-19T10:00:00.074Z DEBUG 24236 --- [HikariPool-1-housekeeper] com.zaxxer.hikari.pool.HikariPool        : HikariPool-1 - Pool stats (total=10, active=7, idle=3, waiting=2)
```

…and distile collapses thousands of them into the handful of patterns actually happening:

```
[SNAPSHOT  2026-07-19T10:02:31.512]  top 10 of 14 templates
    1029  #0  <*> DEBUG <*> --- [http-nio-<*>-exec-<*>] org.hibernate.SQL : select o1_<*>.id,o1_<*>.total,o1_<*>.status from orders o1_<*> where o1_<*>.id=?
    1019  #2  <*> DEBUG <*> --- [HikariPool-<*>-housekeeper] com.zaxxer.hikari.pool.HikariPool : HikariPool-<*> - Pool stats (total=<*>, active=<*>, idle=<*>, waiting=<*>)
     616  #3  <*> INFO <*> --- [http-nio-<*>-exec-<*>] c.e.demo.OrderController : Created order <*> for customer <*>
     298  #5  <*> DEBUG <*> --- [http-nio-<*>-exec-<*>] o.s.web.servlet.DispatcherServlet : POST <*> parameters={masked}
```

**Why `--depth 9`?** Every Spring Boot line starts with the same 7-token prefix
(timestamp, level, PID, `---`, thread, logger, `:`) before the real message. distile
groups by the leading tokens, so the depth has to reach past that prefix to tell events
apart by their message — here it even splits requests by HTTP method. The default
`--depth 4` still gives a tidy ~10-template summary; it just lumps all the
DispatcherServlet lines into one. Going much deeper over-splits. Framework logs simply
need a deeper tree than simple ones.

## Log4j2 appender

For your own JVM apps you can skip the file round-trip and plug distile straight into
Log4j2, so log events are distilled **in-process**. Add distile as a dependency, then
register the appender in your `log4j2.xml`:

```xml
<Configuration>
  <Appenders>
    <Distile name="distile" snapshotInterval="5" topN="10"/>
  </Appenders>
  <Loggers>
    <Root level="info">
      <AppenderRef ref="distile"/>
    </Root>
  </Loggers>
</Configuration>
```

distile ships its Log4j2 plugin descriptor, so the appender is found automatically.

Attributes mirror the CLI flags: `simThreshold`, `depth`, `maxChildren`, `topN`,
`snapshotInterval`, `emitNew`, `json`, `outlierMax`, and `file` (output path; defaults to
stdout).

When you log with parameters — `log.info("user {} logged in from {}", id, ip)` — the
appender knows the `{}` positions are variables and marks them directly, so templates are
clean from the first line. Concatenated messages fall back to the same masking the stdin
path uses. Either way a message clusters to the same template it would via stdin.

The appender sees the *message* before serialization, so its templates carry no timestamp
or level prefix, cleaner than tailing a rendered log file. distile keeps only templates and
counts; it never stores the actual parameter values.

## Design

I/O-free core (`core/`) usable as a library; emission (`emission/`) decides
*when* to surface a template and only reads core state; formatting lives in
`report/`; adapters (stdin/file/tail in `cli/`, the Log4j2 appender in `log4j/`) are
just different sources of lines over the same core. Adding a new input mode never
touches the core — the Log4j2 appender was added without changing a single core file.

## Performance & scale tests

distile is built for long-running processes producing millions of lines, so the
hot path (tokenize → mask → tree → match) is near-O(1) per line and allocates
little. Two test classes check this holds.

The tests only assert on results that don't depend on the machine — template
counts and snapshot consistency. Throughput is measured and printed, but never
asserted, so running on a slow machine can't break the build.

Run them all with `mvn test`, or drive a class directly for ad-hoc measurement:

```bash
mvn test                                                              # includes both
java -cp target/classes:target/test-classes \
     org.korhan.distile.core.Benchmark 1000000                       # N lines
```

**`Benchmark`**: throughput sanity check. Feeds a synthetic log built from ~8
templates with randomized variable parts, reports lines/sec, and asserts the
template count stays small (masking/threshold regressions that cause explosion
fail here).

**`ScaleAndSnapshotTest`**: the high-cardinality, long-running case `Benchmark`
doesn't reach. It generates thousands of *structurally distinct* templates
(distinct tokens in tree-routing positions, so they land in separate leaves) and
takes Top-N snapshots **concurrently with ingest**. It guards two things:

- tree descent and cluster creation stay bounded at scale, thousands of real
  templates form without the fan-out running away;
- `snapshotTopN` taken while the ingest thread mutates counts never throws
  neither a `ConcurrentModificationException` from the copy nor TimSort's
  *"Comparison method violates its general contract"* from sorting live counts.
  (The snapshot copies counts under the lock and sorts outside it, so the ingest
  hot path is never blocked by the O(T log T) sort.)

### Numbers seen

Indicative, single JVM, single ingest thread, JDK 25 / G1 on a laptop — treat as
ballpark, not a spec:

| Workload                                         | Throughput          | Result                         |
| ------------------------------------------------ | ------------------- | ------------------------------ |
| `Benchmark`, 1,000,000 lines (~8 templates)      | ~730k lines/sec     | collapses to 7 templates       |
| `ScaleAndSnapshotTest`, 300,000 lines            | ~990k lines/sec     | forms 4,096 distinct templates |

Masking dominates the per-line cost, so it gets the most attention: reusing regex
`Matcher`s and skipping rules a token provably can't match (a plain word triggers
no numeric/structural rule) took the 1M-line run from ~120k to ~730k lines/sec
and cut young-GC collections from ~121 to ~10.

## Acceptance test

The unit and scale tests drive the core library. One end-to-end test exercises
the **shipped artifact**: `DistilesSpringBootStreamIT` spawns the real
`target/distile.jar` (via `java -jar`, input on stdin — the actual pipe path),
feeds it a deterministic Spring Boot stream from the seeded `LogSimulator`, and
asserts distile's core promise: thousands of lines collapse to a small, bounded
set of templates, hot patterns dominate by count, and rare events surface as
outliers.

It runs under maven-failsafe in the `verify` phase, **after** the JAR is
packaged (so `mvn test` stays fast):

```bash
mvn verify        # packages the JAR, then runs the e2e test
```

## Todos
- [x] Initial implementation core and emission
- [x] Simulator app to try distile
- [x] Check Java 25 requirement — Java 21 is sufficient
- [x] Log adapter for Log4j


## License

[MIT](LICENSE) © Korhan Gülseven

