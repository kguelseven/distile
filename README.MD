# <img src="distile-icon.svg" alt="" height="48" valign="middle"> distile

**Streaming log-template extractor. Distils noisy logs into frequency-ranked patterns, locally.**

A from-scratch Java implementation of the [Drain](https://ieeexplore.ieee.org/document/8029742)
algorithm. Point it at a log stream and it collapses thousands of noisy lines
into a small, readable set of **templates** (the constant skeleton of a message,
with variable parts replaced by `<*>`), ranked by frequency, with an outlier
view.

```
tail -f app.log | ./distile
```

```
[NEW] #0  <*> INFO [http-<*>] http <*> <*> <*> <*>
[NEW] #1  <*> WARN [worker-<*>] db slow query <*> took <*>

== snapshot: top 3 of 12 templates ==
     367  #0  <*> INFO [http-<*>] http <*> <*> <*> <*>
     120  #1  <*> WARN [worker-<*>] db slow query <*> took <*>
      54  #5  <*> ERROR [main] http request <*> failed status <*>
```

## Quick start

Requires **Java 25** and **Maven**.

```bash
mvn package                       # builds target/distile.jar
tail -f app.log | ./distile       # or: ./distile -f app.log
```

Output columns per template: **count**, **#clusterId**, **template**.

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


## Todos
- [x] Initial implementation core and emission
- [x] Simulator app to try distile
- [ ] Check Java 25 requirement
- [ ] Log adapter for Log4j
- [ ] Parameter extraction
- [ ] Template persistence


## License

[MIT](LICENSE) © Korhan Gülseven

