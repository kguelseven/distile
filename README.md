# <img src="distile-icon.svg" alt="" height="48" valign="middle"> distile

**See what your logs are actually saying, without reading every line.**

When an app runs, it writes thousands of log lines, mostly the same
messages over and over, just with different values:

    12:01:03 INFO user 4711 logged in from 10.0.0.1
    12:01:04 INFO user 8892 logged in from 10.0.0.5
    12:01:04 WARN [worker-3] db slow query users took 1043ms
    12:01:05 INFO user 2231 logged in from 10.0.0.9

On a console scrolling by thousands of lines per second, the messages
that matter drown in the noise. distile groups lines of the same shape
into one **template** (the fixed part of the message) with the changing
parts replaced by `<*>` and counts each one:

    367  #0  <*> INFO user <*> logged in from <*>
    120  #1  <*> WARN [worker-<*>] db slow query <*> took <*>

So instead of hundreds of near-identical lines, you see the handful of
things your app is really doing, each with a count, and the rare error
hiding among them finally stands out.

It runs locally on a live stream:

    tail -f app.log | ./distile

Under the hood, distile is a from-scratch Java implementation of the
[Drain](https://ieeexplore.ieee.org/document/8029742) algorithm: a
streaming log-template extractor that distils noisy logs into
frequency-ranked patterns. Point it at a log stream and it
collapses thousands of lines into a small, readable set of templates,
ranked by frequency, with an outlier view.

## Quick start

Requires **Java 25** and **Maven**.

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
      --top-n <n>            templates shown per snapshot        (default 10)
      --snapshot-interval <s> seconds between Top-N snapshots; 0 = off (default 5)
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

## How it works

Every line runs the same near-O(1) pipeline: **tokenize → mask → tree descent →
match → count++**.

- **Mask first.** Variable tokens (timestamps, IPs, UUIDs, hex, numbers) are
  replaced with `<*>` *before* the tree — this is what stops each unique
  timestamp spawning its own branch.
- **Fixed-depth tree.** Lines are bucketed by token count, then by their leading
  tokens, down to a leaf holding a handful of candidate templates. Matching only
  ever compares within one leaf, never globally.
- **Merge to wildcard.** A matching line generalises any disagreeing position to
  `<*>`; templates only lose specificity. Memory is bounded by template count,
  not line count.

> **Note:** variables in *leading* (tree-routing) tokens split into separate
> templates unless masked — an inherent Drain trait. Try `--depth 3` to merge
> more aggressively.

## Try it

A built-in generator emits varied fake logs to exercise distile live:

```bash
./logsim --rate 40 | ./distile --snapshot-interval 3
```

## Design

I/O-free core (`core/`) usable as a library; emission (`emission/`) decides
*when* to surface a template and only reads core state; formatting lives in
`report/`; adapters (stdin/file/tail) live in `cli/`. Adding a new input mode
never touches the core.

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

**`Benchmark`** — throughput sanity check. Feeds a synthetic log built from ~8
templates with randomized variable parts, reports lines/sec, and asserts the
template count stays small (masking/threshold regressions that cause explosion
fail here).

**`ScaleAndSnapshotTest`** — the high-cardinality, long-running case `Benchmark`
doesn't reach. It generates thousands of *structurally distinct* templates
(distinct tokens in tree-routing positions, so they land in separate leaves) and
takes Top-N snapshots **concurrently with ingest**. It guards two things:

- tree descent and cluster creation stay bounded at scale — thousands of real
  templates form without the fan-out running away;
- `snapshotTopN` taken while the ingest thread mutates counts never throws —
  neither a `ConcurrentModificationException` from the copy nor TimSort's
  *"Comparison method violates its general contract"* from sorting live counts.
  (The snapshot copies counts under the lock and sorts outside it, so the ingest
  hot path is never blocked by the O(T log T) sort.)

### Numbers seen

Indicative, single JVM, single ingest thread, JDK 25 / G1 on a laptop — treat as
ballpark, not a spec:

| Workload                                         | Throughput          | Result                         |
| ------------------------------------------------ | ------------------- | ------------------------------ |
| `Benchmark`, 1,000,000 lines (~8 templates)      | ~756k lines/sec     | collapses to 7 templates       |
| `ScaleAndSnapshotTest`, 300,000 lines            | ~960k lines/sec     | forms 4,096 distinct templates |

Masking dominates the per-line cost, so it gets the most attention: reusing regex
`Matcher`s and skipping rules a token provably can't match (a plain word triggers
no numeric/structural rule) took the 1M-line run from ~120k to ~756k lines/sec
and cut young-GC collections from ~121 to ~10.

## Todos
- [x] Initial implementation core and emission
- [x] Simulator app to try distile
- [ ] Check Java 25 requirement
- [ ] Log adapter for Log4j
- [ ] Parameter extraction
- [ ] Template persistence


## License

[MIT](LICENSE) © Korhan Gülseven

