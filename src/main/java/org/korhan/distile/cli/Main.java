package org.korhan.distile.cli;

import org.korhan.distile.core.DrainConfig;
import org.korhan.distile.core.DrainTree;
import org.korhan.distile.core.LogCluster;
import org.korhan.distile.core.MaskRule;
import org.korhan.distile.core.Masker;
import org.korhan.distile.emission.EmissionPolicy;
import org.korhan.distile.emission.SnapshotScheduler;
import org.korhan.distile.report.JsonReporter;
import org.korhan.distile.report.Reporter;
import org.korhan.distile.report.TextReporter;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * CLI entry point: wires the I/O-free core to an ingest loop and the emission
 * layer, holding all threading, I/O and flag handling that core must
 * stay free of. The wiring is one-directional: Masker → DrainTree feeds
 * MatchResults to an EmissionPolicy while a SnapshotScheduler
 * polls the tree's snapshot view; core never calls back into either.
 */
@Command(
        name = "distile",
        mixinStandardHelpOptions = true,
        version = "distile 0.1.0",
        description = "Distil noisy log streams into frequency-ranked templates (Drain algorithm).")
public final class Main implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "FILE",
            description = "Log file to read. Omit to read from stdin.")
    private Path fileArg;

    @Option(names = {"-f", "--file"}, description = "Log file to read (same as positional FILE).")
    private Path fileOpt;

    @Option(names = "--tail", description = "Keep reading appended lines (like tail -f). Files only.")
    private boolean tail;

    @Option(names = "--sim-threshold", defaultValue = "0.5",
            description = "Min similarity to join a cluster, 0..1 (default: ${DEFAULT-VALUE}).")
    private double simThreshold;

    @Option(names = "--depth", defaultValue = "4",
            description = "Parse-tree depth (default: ${DEFAULT-VALUE}).")
    private int depth;

    @Option(names = "--max-children", defaultValue = "100",
            description = "Max children per tree node before <*> overflow (default: ${DEFAULT-VALUE}).")
    private int maxChildren;

    @Option(names = "--masks-file",
            description = "Custom mask rules file (replaces defaults). One 'regex' or 'regex<TAB>replacement' per line; '#' comments.")
    private Path masksFile;

    @Option(names = "--top-n", defaultValue = "10",
            description = "How many templates to show in snapshots (default: ${DEFAULT-VALUE}).")
    private int topN;

    @Option(names = "--snapshot-interval", defaultValue = "5",
            description = "Seconds between Top-N snapshots; 0 disables (default: ${DEFAULT-VALUE}).")
    private long snapshotInterval;

    @Option(names = "--no-emit-new", description = "Disable new-template events (on by default).")
    private boolean noEmitNew;

    @Option(names = "--milestones", arity = "0..1", fallbackValue = "DEFAULT",
            description = "Emit on count milestones. No value = powers of ten; or pass e.g. 1,50,500. Off if omitted.")
    private String milestones;

    @Option(names = "--outlier-max", defaultValue = "2",
            description = "Templates with count <= this are shown as outliers in the final report (default: ${DEFAULT-VALUE}).")
    private long outlierMax;

    @Option(names = "--json", description = "Emit JSONL instead of text.")
    private boolean json;

    @Override
    public Integer call() throws Exception {
        Masker masker = buildMasker();
        DrainConfig config = new DrainConfig(depth, maxChildren, simThreshold);
        DrainTree tree = new DrainTree(config, masker);

        Reporter reporter = json ? new JsonReporter(System.out) : new TextReporter(System.out);
        EmissionPolicy policy = EmissionPolicy.of(!noEmitNew, parseMilestones(), reporter);

        SnapshotScheduler scheduler = new SnapshotScheduler(tree, reporter, topN, snapshotInterval);
        scheduler.start();

        // Final report must fire exactly once: whether the stream ends normally
        // or the user hits Ctrl-C (shutdown hook). Guard against a double fire.
        AtomicBoolean finalized = new AtomicBoolean(false);
        Runnable emitFinal = () -> {
            if (finalized.compareAndSet(false, true)) {
                scheduler.close();
                List<LogCluster> all = tree.snapshotAll();
                reporter.emit(EmissionPolicy.buildFinal(all, tree.clusterCount(), outlierMax));
                reporter.flush();
            }
        };
        Runtime.getRuntime().addShutdownHook(new Thread(emitFinal, "distile-final"));

        LineSource source = resolveSource();
        try {
            source.forEachLine(line -> policy.onMatch(tree.add(line)));
        } finally {
            emitFinal.run();
        }
        return 0;
    }

    private LineSource resolveSource() {
        Path file = fileOpt != null ? fileOpt : fileArg;
        return file == null ? LineSource.ofStdin() : LineSource.ofFile(file, tail);
    }

    private Masker buildMasker() throws IOException {
        if (masksFile == null) {
            return Masker.withDefaults();
        }
        List<MaskRule> rules = new ArrayList<>();
        for (String raw : Files.readAllLines(masksFile, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab >= 0) {
                rules.add(new MaskRule(Pattern.compile(line.substring(0, tab)), line.substring(tab + 1)));
            } else {
                rules.add(MaskRule.of(line));
            }
        }
        return new Masker(rules);
    }

    private long[] parseMilestones() {
        if (milestones == null) {
            return new long[0];
        }
        if (milestones.equals("DEFAULT")) {
            return EmissionPolicy.DEFAULT_MILESTONES;
        }
        String[] parts = milestones.split(",");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Long.parseLong(parts[i].strip());
        }
        return out;
    }

    public static void main(String[] args) {
        int exit = new CommandLine(new Main()).execute(args);
        System.exit(exit);
    }
}
