# distile: design & internals

Companion to the [README](../README.md), which covers what distile is and how to use it.
This document covers how distile is built: the architecture, the per-line algorithm,
terminal handling for the live view, and the test strategy.

## Architecture

Three layers with a strict one-way dependency. Adding a new way to feed distile lines never
touches the core. The Log4j2 appender was added without changing a single core file.

- **`core/`** holds the I/O-free library: `Tokenizer`, `Masker`, `DrainTree`, `LogCluster`.
  It runs the clustering on every line and keeps the state (templates and counts). It never
  prints, and knows nothing about the layers above it.
- **`emission/`** decides *when* a template surfaces to the user (new template, interval
  snapshot, count milestone, end-of-stream final). It only reads core state and never changes
  it. Emission-side bookkeeping, such as which milestone a cluster last crossed, lives here,
  never on `LogCluster`.
- **`report/`** does formatting only: it turns emission events into plain text, JSONL, or the
  live `--top` view.
- **adapters** are different *sources of lines* over the same core: stdin, file, and `--tail`
  in `cli/`, plus the in-process Log4j2 appender in `log4j/`.

This is the payoff of the I/O-free core: every frontend is a thin wrapper that calls
`add(line) -> MatchResult` and reads a snapshot view. If a proposed adapter needs core
changes, the boundary is wrong.

## The pipeline (per line)

Every line runs the same near-O(1) path: **tokenize → mask → tree descent → match →
count++**. Per-line cost does not depend on how many lines have been seen. Matching only ever
compares against the handful of clusters in one leaf, never all clusters globally.

1. **Tokenize:** split on whitespace into tokens.
2. **Mask:** replace known-variable tokens (timestamps, IPs, UUIDs, hex blobs, numbers) with
   `<*>` *before* the tree. This is the highest-leverage step. Masking replaces in place. It
   never deletes a token, so token position and count stay the same. A variable at the
   *start* of a line (a timestamp!) would otherwise create a new tree branch per value.
   Masking it first means every line starts with a stable `<*>` and collapses correctly. The
   more you mask up front, the less the tree has to do.
3. **Tree descent:** bucket by token count (level 1), then by the leading tokens (levels
   2..depth), down to a leaf that holds a few candidate templates. Fan-out per node is capped
   by `maxChildren`. Overflow goes into a dedicated `<*>` branch, so adversarial input cannot
   grow the tree without bound.
4. **Match and merge:** within the leaf, pick the best template above `simThreshold` (a `<*>`
   in the template matches any token). On a match, generalise any differing position to `<*>`
   and increment the count. Otherwise create a new cluster from the masked line. Templates
   only ever *lose* detail, so memory is bounded by the number of templates, not the number
   of lines. Lines are read and thrown away.

### Tree depth and framework prefixes

distile groups by leading tokens, so depth must reach past any fixed prefix to tell events
apart by their *message*. Real Spring Boot / Logback lines begin with the same ~7-token
prefix (timestamp, level, PID, `---`, thread, logger, `:`), so `--depth 9` is needed to
separate them by message. At that depth it even splits requests by HTTP method. The default
`--depth 4` still gives a tidy summary; it just lumps all the DispatcherServlet lines
together. Going much deeper over-splits. Framework logs simply need a deeper tree than simple
ones.

One related Drain trait is built in: variables in *leading* (tree-routing) tokens split into
separate templates unless masked. And the same event with an optional trailing clause lands
in a different token-count bucket and won't merge. Deleting tokens to fix that would break
position-based matching for everything else, so distile accepts it for now.

## Terminal handling for `--top`

The live view uses [JLine](https://github.com/jline/jline3) for terminal-width detection and
capability-driven cursor control. A few non-obvious decisions:

- **Piped stdin.** The common case `tail -f app.log | distile --top` redirects stdin, and
  that defeats JLine's standard system terminal: it binds to the redirected stdin handle and
  falls back to a dumb terminal. So distile looks at what is actually redirected and picks a
  terminal to match:
    - stdout is not a terminal (for example `> out.txt`): fall back to plain text.
    - stdin is also a terminal: use the ordinary system terminal.
    - stdin is redirected: attach to the controlling terminal through `/dev/tty` with JLine's
      exec provider, which reads size and capabilities from `stty` and `infocmp` no matter how
      stdin and stdout are redirected.

  This path is POSIX-only; the plain-text fallback covers everything else.
- **Manual repaint, not diffing.** Each frame is a full repaint: move to the top-left
  (`cursor_home`), print each line followed by `clr_eol`, then clear the rest of the screen
  with `clr_eos`. distile does this instead of JLine's diff-based `Display`, which got out of
  sync on long lines. Every line is cut to the terminal width minus one so it cannot wrap, and
  auto-wrap is turned off (`rmam`) while the view is up, as an extra guard.
- **Flash-and-fade highlight.** When a template *enters* the top-N it flashes bright cyan,
  then fades through dimmer teal back to the default colour over the next few frames. Only new
  entries flash. A rising count does not (almost every row rises every frame), so the glow
  reliably means "a new pattern just appeared."
- **Clean exit.** Ctrl-C leaves the alternate screen and restores the cursor with no final
  dump, since the user was already watching the live view. The end-of-stream summary is
  printed only in the fallback or redirected case, where that text is the whole output.
- **Fat-jar note.** JLine selects its terminal provider via `ServiceLoader`, so the shade
  build merges service files with `ServicesResourceTransformer`; without it a shaded JLine
  finds only a dumb terminal. The `distile` launcher passes `--enable-native-access=ALL-UNNAMED`
  to silence the JDK's native-provider warning.

## Performance & scale tests

distile is built for long-running processes producing millions of lines, so the hot path
(tokenize → mask → tree → match) is near-O(1) per line and allocates little. Two test classes
check this holds. They assert only on results that don't depend on the machine: template
counts and snapshot consistency. Throughput is measured and printed, but never asserted, so a
slow machine can't break the build.

```bash
mvn test                                                              # includes both
java -cp target/classes:target/test-classes \
     org.korhan.distile.core.Benchmark 1000000                       # N lines
```

**`Benchmark`** is a throughput sanity check. It feeds a synthetic log built from about 8
templates with randomized variable parts, reports lines/sec, and asserts the template count
stays small (masking or threshold regressions that cause explosion fail here).

**`ScaleAndSnapshotTest`** covers the high-cardinality, long-running case that `Benchmark`
doesn't reach. It generates thousands of *structurally distinct* templates (distinct tokens in
tree-routing positions, so they land in separate leaves) and takes Top-N snapshots **while
ingest is running**. It guards two things:

- tree descent and cluster creation stay bounded at scale: thousands of real templates form
  without the fan-out running away;
- `snapshotTopN`, taken while the ingest thread changes counts, never throws: not a
  `ConcurrentModificationException` from the copy, and not TimSort's *"Comparison method
  violates its general contract"* from sorting live counts. The snapshot copies counts under
  the lock and sorts outside it, so the ingest hot path is never blocked by the O(T log T)
  sort.

### Numbers seen

Indicative only: single JVM, single ingest thread, JDK 25 / G1 on a laptop. Treat as a
ballpark, not a spec:

| Workload                                         | Throughput          | Result                         |
| ------------------------------------------------ | ------------------- | ------------------------------ |
| `Benchmark`, 1,000,000 lines (~8 templates)      | ~730k lines/sec     | collapses to 7 templates       |
| `ScaleAndSnapshotTest`, 300,000 lines            | ~990k lines/sec     | forms 4,096 distinct templates |

Masking dominates the per-line cost, so it gets the most attention. Reusing regex `Matcher`s
and skipping rules a token can't possibly match (a plain word triggers no numeric or
structural rule) took the 1M-line run from ~120k to ~730k lines/sec and cut young-GC
collections from ~121 to ~10.

## Acceptance test

The unit and scale tests drive the core library. One end-to-end test exercises the **shipped
artifact**: `DistilesSpringBootStreamIT` spawns the real `target/distile.jar` (via
`java -jar`, input on stdin, the real pipe path), feeds it a deterministic Spring Boot stream
from the seeded `LogSimulator`, and asserts distile's core promise: thousands of lines
collapse to a small, bounded set of templates, hot patterns dominate by count, and rare events
surface as outliers.

It runs under maven-failsafe in the `verify` phase, **after** the JAR is packaged (so
`mvn test` stays fast):

```bash
mvn verify        # packages the JAR, then runs the e2e test
```
