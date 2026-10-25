package welt.runner;

import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.PivotVoter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

/**
 * Benchmark harness for one {@code (dataset, minSup, minWeight)} cell:
 * <ul>
 *   <li>a probe run (also the first warmup); if it times out the cell is reported from that
 *       single probe and not repeated;</li>
 *   <li>{@code WARMUP - 1} further warmup runs (discarded), then {@code MEASURED} runs;
 *       the runtime is reported as median, min, max and standard deviation of
 *       {@code totalMs} (see {@link RunHarness} for what the clock covers);</li>
 *   <li>counting metrics (candidates, iso-calls, #FWS) are deterministic; the CSV records
 *       whether they were identical across the measured runs.</li>
 * </ul>
 *
 * <p>{@code java welt.runner.BenchmarkMain <ds> <minSup> <minWeight> <budget> <limitMs> [warmup] [measured]}
 *
 * <p>With {@code BENCH_CSV} set, the header and one row per algorithm are printed with the
 * prefix {@code CSV,}. {@code BENCH_ALGOS} selects the algorithms (comma or space separated).
 * With {@code DUMP_PATTERNS=<dir>} the result set of every completed cell is written by
 * {@link PatternDump} after the measured runs.
 */
public final class BenchmarkMain {

    /** Process exit code when a run ended with OutOfMemoryError (its CSV row is still printed). */
    static final int EXIT_OOM = 3;
    /** Process exit code when an exact-mode run reports an aborted embedding search. */
    static final int EXIT_EXACT_VIOLATION = 4;
    private static boolean anyOom = false;

    private static int WARMUP = 1;
    private static int MEASURED = 5;

    private static final boolean CSV = System.getenv("BENCH_CSV") != null;

    /**
     * Matching-order configuration of each algorithm. WeLT uses {@code WELT_VOTER=α,β,γ} and
     * {@code WELT_WORDER=on|off} (default when unset: (1,1,1) and on; the CSV column
     * voterSource tells which). The baselines keep the shared default: degree-only
     * pivot, no weight-aware order.
     */
    /** An on/off environment switch, {@code unset} when the variable is not set. */
    private static boolean onOff(String name, boolean unset) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return unset;
        if (v.equals("off")) return false;
        if (v.equals("on")) return true;
        throw new IllegalArgumentException(name + " must be on or off, got " + v);
    }

    static RunHarness.Config configure(RunHarness.Config cfg) {
        cfg.liveHeap = onOff("WELT_LIVE_HEAP", false);
        if (cfg.algo.equals("WeLT")) {
            String v = System.getenv("WELT_VOTER");
            String w = System.getenv("WELT_WORDER");
            cfg.voter = (v == null || v.isBlank()) ? PivotVoter.WELT_DEFAULT : PivotVoter.parse(v);
            if (w != null && !w.isBlank() && !w.equals("on") && !w.equals("off"))
                throw new IllegalArgumentException("WELT_WORDER must be on or off, got " + w);
            cfg.weightOrder = w == null || w.isBlank() || w.equals("on");
            cfg.tableReuse = onOff("WELT_TABLE_REUSE", true);
            cfg.tableThreshold = onOff("WELT_TABLE_THRESHOLD", true);
            orderMode(cfg, System.getenv("WELT_ORDER_MODE"));
        } else {
            cfg.voter = PivotVoter.DEGREE_ONLY;
            cfg.weightOrder = false;
        }
        return cfg;
    }

    /**
     * Order mode of the voting analysis ({@code vote}, {@code index}, {@code reverse}, {@code cost} or
     * {@code random:<seed>}); unset or {@code vote} keeps the voter.
     */
    static void orderMode(RunHarness.Config cfg, String mode) {
        if (mode == null || mode.isBlank()) return;
        if (mode.startsWith("random:")) {
            cfg.orderMode = welt.core.MniSupportCounter.OrderMode.RANDOM;
            cfg.orderSeed = Long.parseLong(mode.substring("random:".length()));
        } else {
            cfg.orderMode = welt.core.MniSupportCounter.OrderMode.valueOf(mode.toUpperCase(Locale.ROOT));
        }
    }

    /** CSV label of the order: the voter, or {@code order:<mode>} when an order mode replaces it. */
    static String orderLabel(RunHarness.Config cfg) {
        if (cfg.orderMode == welt.core.MniSupportCounter.OrderMode.VOTE) return cfg.voter.label();
        String m = cfg.orderMode.name().toLowerCase(Locale.ROOT);
        return "order:" + (cfg.orderMode == welt.core.MniSupportCounter.OrderMode.RANDOM ? m + ":" + cfg.orderSeed : m);
    }

    static String voterSource(String algo) {
        if (!algo.equals("WeLT")) return "baseline";
        String v = System.getenv("WELT_VOTER");
        return (v == null || v.isBlank()) ? "default" : "env";
    }
    private static boolean headerPrinted = false;

    /**
     * Weight semantics of each strategy, recorded as a CSV column so result aggregation never
     * confuses non-comparable result sets: {@code none} (GraMi, unweighted reference),
     * {@code edge} (EdgeTypeBound, EdgeTypeBound-ungated and WeLT: the same edge-bottleneck
     * result set), {@code edge-label} (WEGM: edge weights are pattern labels, a different result
     * set), {@code vertex} (OWGraMi, a different vertex-bottleneck result set).
     */
    static String weightModel(String algo) {
        switch (algo) {
            case "EdgeTypeBound": case "EdgeTypeBound-ungated": case "GraMiFilter": case "WeLT": return "edge";
            case "WEGM":    return "edge-label";
            case "OWGraMi": return "vertex";
            default:        return "none";
        }
    }

    /** {@code exact} when no search budget is set (budget 0), {@code approx} otherwise. */
    static String mode(long budget) {
        return budget <= 0 ? "exact" : "approx";
    }

    /**
     * Exact-mode guard: in exact mode no embedding search may have been aborted by a budget.
     * Returns an error message, or {@code null} when the run is consistent with its mode.
     */
    static String exactModeViolation(long budget, long budgetExceeded) {
        if (mode(budget).equals("exact") && budgetExceeded != 0) {
            return "exact mode (budget=" + budget + ") but budgetExceeded=" + budgetExceeded
                    + ": an embedding search was aborted, so MNI is not exact";
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--header")) {
            // The CSV header, so a driver script can write a row for a process that died
            // before printing one.
            System.out.println("CSV," + buildRow("", "", 0, 0, 0, 0, List.of(), "", 0).header());
            return;
        }
        Path file = Path.of(args[0]);
        int minSup = Integer.parseInt(args[1]);
        double minWeight = Double.parseDouble(args[2]);
        long budget = args.length > 3 ? Long.parseLong(args[3]) : 0;
        long limitMs = args.length > 4 ? Long.parseLong(args[4]) : 0; // 0 = no limit
        if (args.length > 5) WARMUP = Integer.parseInt(args[5]);
        if (args.length > 6) MEASURED = Integer.parseInt(args[6]);

        LabeledWeightedGraph g = new LgGraphReader().read(file).graph;
        String ds = file.getFileName().toString();
        System.out.printf(Locale.ROOT, "== Benchmark | %s | minSup=%d minWeight=%.1f mode=%s budget=%d | limit=%s | warmup=%d measured=%d ==%n",
                ds, minSup, minWeight, mode(budget), budget,
                limitMs == 0 ? "none" : (limitMs + "ms"), WARMUP, MEASURED);
        System.out.printf(Locale.ROOT, "%-9s %-7s %6s %8s %10s %12s %12s %6s %8s%n",
                "algo", "model", "#FWS", "candMNI", "MINEmni", "table(ms)", "total(ms)", "T.O.", "memMB");

        String algosEnv = System.getenv("BENCH_ALGOS");
        String[] algos = (algosEnv == null || algosEnv.isBlank())
                ? new String[]{"GraMi", "EdgeTypeBound", "WEGM", "WeLT", "OWGraMi"}
                : algosEnv.trim().split("[,\\s]+");
        for (String algo : algos) {
            benchmark(algo, g, ds, minSup, minWeight, budget, limitMs);
        }
        if (anyOom) System.exit(EXIT_OOM);
    }

    /** The measured runs of one cell, its status (OK, TO or OOM) and how many runs were not OK. */
    static final class Cell {
        final List<RunHarness.Result> runs;
        final String status;
        final int nonOk;

        Cell(List<RunHarness.Result> runs, String status, int nonOk) {
            this.runs = runs;
            this.status = status;
            this.nonOk = nonOk;
        }
    }

    private static RunHarness.Result runChecked(LabeledWeightedGraph g, RunHarness.Config cfg, String ds) {
        RunHarness.Result r = RunHarness.runOnce(g, cfg);
        String violation = exactModeViolation(cfg.budget, r.m.budgetExceeded);
        if (violation != null) {
            System.err.println("ERROR: " + cfg.algo + " " + ds + " minSup=" + cfg.minSup + ": " + violation);
            System.exit(EXIT_EXACT_VIOLATION);
        }
        return r;
    }

    /**
     * The measurement protocol of one cell, shared by BenchmarkMain and AblationMain: a probe
     * run (the first warmup); a TO or OOM probe is reported alone; otherwise {@code warmup - 1}
     * further discarded runs and {@code measured} reported runs.
     */
    static Cell measureCell(LabeledWeightedGraph g, RunHarness.Config cfg, String ds, int warmup, int measured) {
        RunHarness.Result probe = runChecked(g, cfg, ds);
        List<RunHarness.Result> runs = new ArrayList<>();
        String status = probe.status;
        int nonOk = 0;
        if (probe.partial()) {
            runs.add(probe); // a TO or OOM cell is a single probe run
            nonOk = 1;
        } else {
            for (int i = 1; i < warmup && status.equals("OK"); i++) {
                RunHarness.Result w = runChecked(g, cfg, ds);
                if (w.partial()) status = w.status;
            }
            for (int i = 0; i < measured; i++) {
                RunHarness.Result r = runChecked(g, cfg, ds);
                if (r.partial()) {
                    nonOk++;
                    if (!status.equals("OOM")) status = r.status; // OOM outranks TO
                }
                runs.add(r);
            }
        }
        if (status.equals("OOM")) anyOom = true;
        return new Cell(runs, status, nonOk);
    }

    static boolean anyOom() {
        return anyOom;
    }

    private static void benchmark(String algo, LabeledWeightedGraph g, String ds,
                                  int minSup, double minWeight, long budget, long limitMs) throws java.io.IOException {
        RunHarness.Config cfg = configure(new RunHarness.Config(algo, minSup, minWeight));
        cfg.budget = budget;
        cfg.limitMs = limitMs;
        Cell cell = measureCell(g, cfg, ds, WARMUP, MEASURED);
        report(algo, ds, minSup, minWeight, budget, limitMs, cell.runs, cell.status, cell.nonOk);
        String dumpDir = System.getenv("DUMP_PATTERNS");
        if (dumpDir != null && !dumpDir.isBlank()) {
            if (cell.status.equals("OK")) {
                // after the timed runs: W is recomputed outside every measurement
                boolean labels = RunHarness.usesWeightLabels(algo);
                int n = PatternDump.write(Path.of(dumpDir), labels ? welt.strategy.WEGMStrategy.weightLabeledGraph(g) : g,
                        labels, ds, algo, minSup, minWeight, cell.runs.get(cell.runs.size() - 1).patterns);
                System.out.println("patterns: " + n + " -> " + Path.of(dumpDir).resolve(
                        PatternDump.fileName(ds, algo, minSup, minWeight)));
            } else {
                System.out.println("patterns: not written (status " + cell.status + ", the result set is partial)");
            }
        }
    }

    private static void report(String algo, String ds, int minSup, double minWeight, long budget, long limitMs,
                               List<RunHarness.Result> runs, String status, int nonOk) {
        RunHarness.Result last = runs.get(runs.size() - 1);
        boolean deterministic = true;
        for (RunHarness.Result r : runs) {
            if (r.m.resultCount != last.m.resultCount || r.m.candidateCount != last.m.candidateCount
                    || r.m.mniIsoCalls != last.m.mniIsoCalls) {
                deterministic = false;
            }
        }
        if (!deterministic) {
            System.err.println("WARNING: " + algo + " " + ds + " minSup=" + minSup
                    + ": counting metrics differ across measured runs");
        }
        System.out.printf(Locale.ROOT, "%-9s %-7s %6d %8d %10d %12.1f %12.1f %6s %8.1f%n",
                algo, weightModel(algo), last.m.resultCount, last.m.candidateCount, last.mniIsoCallsMine(),
                median(runs, r -> r.tableBuildMs), median(runs, r -> r.totalMs),
                status, median(runs, r -> r.peakMB));

        if (!CSV) return;
        RunHarness.Row row = buildRow(ds, algo, minSup, minWeight, budget, limitMs, runs, status, nonOk);
        if (!headerPrinted) {
            System.out.println("CSV," + row.header());
            headerPrinted = true;
        }
        System.out.println("CSV," + row.values());
        if (!deterministic) System.err.println("(deterministic=0 recorded for this row)");
    }

    /**
     * The CSV row of one cell. With an empty {@code runs} list it yields the header with
     * empty values (used by {@code --header}).
     */
    static RunHarness.Row buildRow(String ds, String algo, int minSup, double minWeight, long budget, long limitMs,
                                   List<RunHarness.Result> runs, String status, int nonOk) {
        boolean none = runs.isEmpty();
        RunHarness.Result last = none ? null : runs.get(runs.size() - 1);
        boolean deterministic = true;
        for (RunHarness.Result r : runs) {
            if (r.m.resultCount != last.m.resultCount || r.m.candidateCount != last.m.candidateCount
                    || r.m.mniIsoCalls != last.m.mniIsoCalls) {
                deterministic = false;
            }
        }
        return new RunHarness.Row()
                .put("dataset", ds)
                .put("algorithm", algo)
                .put("weightModel", none ? "" : weightModel(algo))
                .put("minSup", minSup)
                .put("minWeight", minWeight)
                .put("mode", mode(budget))
                .put("budget", budget)
                .put("limitMs", limitMs)
                .put("warmup", WARMUP)
                .put("runs", runs.size())
                .put("status", status)
                .put("partial", none ? "" : (status.equals("OK") ? 0 : 1))
                .put("nonOkRuns", none ? "" : nonOk)
                .put("N", none ? "" : last.m.generated)
                .put("prunedStrategy", none ? "" : last.m.prunedByStrategy)
                .put("prunedP1", none ? "" : last.m.prunedByP1)
                .put("prunedP2", none ? "" : last.m.prunedByP2)
                .put("prunedP2Single", none ? "" : last.m.prunedByP2Single)
                .put("prunedEdgeType", none ? "" : last.m.prunedByEdgeType)
                .put("prunedP3", none ? "" : last.m.prunedByApriori)
                .put("M", none ? "" : last.m.candidateCount)
                .put("Mtotal", none ? "" : last.candidatesTotal())
                .put("Mprime", none ? "" : last.m.mniFrequent)
                .put("R", none ? "" : last.m.resultCount)
                .put("extended", none ? "" : last.m.extended)
                .put("visitedSize", none ? "" : last.m.generated)
                .put("frequentSize", none ? "" : last.m.frequentMemoSize)
                .put("frontierPeak", none ? "" : last.m.frontierPeak)
                .put("visitedCodeChars", none ? "" : last.m.visitedCodeChars)
                .put("memoCodeChars", none ? "" : last.m.memoCodeChars)
                .put("frontierDomainIntsPeak", none ? "" : last.m.frontierDomainIntsPeak)
                .put("tableKeptPatterns", none ? "" : last.m.tableKeptPatterns)
                .put("tableDomainInts", none ? "" : last.m.tableDomainInts)
                .put("liveHeap", none ? "" : (last.cfg.liveHeap ? "on" : "off"))
                .put("liveHeapGraphMB", none ? "" : last.m.liveHeapGraphMB)
                .put("liveHeapPeakMB", none || !last.cfg.liveHeap ? "" : last.m.liveHeapPeakMB)
                .put("liveHeapSamples", none || !last.cfg.liveHeap ? "" : last.m.liveHeapSamples)
                .put("mniIsoCallsMine", none ? "" : last.mniIsoCallsMine())
                .put("mniIsoCallsTotal", none ? "" : last.m.mniIsoCalls)
                .put("weightIsoCalls", none ? "" : last.m.weightIsoCalls)
                .put("tableSize", none ? "" : last.tableSize)
                .put("tableBuildCandidates", none ? "" : last.m.tableBuildCandidates)
                .put("tableBuildMniIsoCalls", none ? "" : last.m.tableBuildMniIsoCalls)
                .put("tableBuildWeightIsoCalls", none ? "" : last.m.tableBuildWeightIsoCalls)
                .put("tableReuse", none ? "" : (last.cfg.tableReuse ? "on" : "off"))
                .put("tableThreshold", none ? "" : (last.cfg.tableThreshold ? "on" : "off"))
                .put("supportReused", none ? "" : last.m.supportReused)
                .put("weightReused", none ? "" : last.m.weightReused)
                .put("budgetExceeded", none ? "" : last.m.budgetExceeded)
                .put("voter", none ? "" : orderLabel(last.cfg))
                .put("worder", none ? "" : (last.cfg.weightOrder ? "on" : "off"))
                .put("voterSource", none ? "" : voterSource(algo))
                .put("backtrack", none ? "" : last.m.backtrackNodes)
                .put("backtrackMni", none ? "" : last.m.backtrackMni)
                .put("backtrackWeight", none ? "" : last.m.backtrackWeight)
                .put("voteMs", none ? "" : median(runs, r -> r.m.voteNanos / 1e6))
                .put("deterministic", none ? "" : deterministic ? 1 : 0)
                .put("indexMs", none ? "" : median(runs, r -> r.indexMs))
                .put("prepMs", none ? "" : median(runs, r -> r.prepMs))
                .put("tableBuildMs", none ? "" : median(runs, r -> r.tableBuildMs))
                .put("mineMs", none ? "" : median(runs, r -> r.mineMs))
                .put("totalMs", none ? "" : median(runs, r -> r.totalMs))
                .put("totalMinMs", none ? "" : min(runs, r -> r.totalMs))
                .put("totalMaxMs", none ? "" : max(runs, r -> r.totalMs))
                .put("totalSdMs", none ? "" : sd(runs, r -> r.totalMs))
                .put("peakMemMB", none ? "" : median(runs, r -> r.peakMB))
                .put("peakMemMaxMB", none ? "" : max(runs, r -> r.peakMB));
    }

    static double median(List<RunHarness.Result> runs, ToDoubleFunction<RunHarness.Result> f) {
        List<Double> v = new ArrayList<>();
        for (RunHarness.Result r : runs) v.add(f.applyAsDouble(r));
        Collections.sort(v);
        int n = v.size();
        return n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2.0;
    }

    static double min(List<RunHarness.Result> runs, ToDoubleFunction<RunHarness.Result> f) {
        double m = Double.POSITIVE_INFINITY;
        for (RunHarness.Result r : runs) m = Math.min(m, f.applyAsDouble(r));
        return m;
    }

    static double max(List<RunHarness.Result> runs, ToDoubleFunction<RunHarness.Result> f) {
        double m = Double.NEGATIVE_INFINITY;
        for (RunHarness.Result r : runs) m = Math.max(m, f.applyAsDouble(r));
        return m;
    }

    /** Sample standard deviation (n-1); 0 for a single run. */
    static double sd(List<RunHarness.Result> runs, ToDoubleFunction<RunHarness.Result> f) {
        int n = runs.size();
        if (n < 2) return 0.0;
        double mean = 0;
        for (RunHarness.Result r : runs) mean += f.applyAsDouble(r);
        mean /= n;
        double ss = 0;
        for (RunHarness.Result r : runs) {
            double d = f.applyAsDouble(r) - mean;
            ss += d * d;
        }
        return Math.sqrt(ss / (n - 1));
    }
}
