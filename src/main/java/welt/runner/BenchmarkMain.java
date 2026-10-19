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
     * voterSource tells which). GraMi, WEGM and OWGraMi keep the shared default: degree-only
     * pivot, no weight-aware order.
     */
    static RunHarness.Config configure(RunHarness.Config cfg) {
        if (cfg.algo.equals("WeLT")) {
            String v = System.getenv("WELT_VOTER");
            String w = System.getenv("WELT_WORDER");
            cfg.voter = (v == null || v.isBlank()) ? PivotVoter.WELT_DEFAULT : PivotVoter.parse(v);
            if (w != null && !w.isBlank() && !w.equals("on") && !w.equals("off"))
                throw new IllegalArgumentException("WELT_WORDER must be on or off, got " + w);
            cfg.weightOrder = w == null || w.isBlank() || w.equals("on");
        } else {
            cfg.voter = PivotVoter.DEGREE_ONLY;
            cfg.weightOrder = false;
        }
        return cfg;
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
     * {@code edge} (WEGM and WeLT, the same edge-bottleneck result set), {@code vertex}
     * (OWGraMi, a different vertex-bottleneck result set).
     */
    static String weightModel(String algo) {
        switch (algo) {
            case "WEGM": case "WeLT": return "edge";
            case "OWGraMi":           return "vertex";
            default:                  return "none";
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
                ? new String[]{"GraMi", "WEGM", "WeLT", "OWGraMi"}
                : algosEnv.trim().split("[,\\s]+");
        for (String algo : algos) {
            benchmark(algo, g, ds, minSup, minWeight, budget, limitMs);
        }
        if (anyOom) System.exit(EXIT_OOM);
    }

    private static RunHarness.Result run(String algo, LabeledWeightedGraph g, String ds,
                                         int minSup, double minWeight, long budget, long limitMs) {
        RunHarness.Config cfg = configure(new RunHarness.Config(algo, minSup, minWeight));
        cfg.budget = budget;
        cfg.limitMs = limitMs;
        RunHarness.Result r = RunHarness.runOnce(g, cfg);
        String violation = exactModeViolation(budget, r.m.budgetExceeded);
        if (violation != null) {
            System.err.println("ERROR: " + algo + " " + ds + " minSup=" + minSup + ": " + violation);
            System.exit(EXIT_EXACT_VIOLATION);
        }
        return r;
    }

    private static void benchmark(String algo, LabeledWeightedGraph g, String ds,
                                  int minSup, double minWeight, long budget, long limitMs) {
        RunHarness.Result probe = run(algo, g, ds, minSup, minWeight, budget, limitMs);
        List<RunHarness.Result> measured = new ArrayList<>();
        String status = probe.status;
        int nonOk = 0;
        if (probe.partial()) {
            measured.add(probe); // a TO or OOM cell is a single probe run
            nonOk = 1;
        } else {
            for (int i = 1; i < WARMUP && status.equals("OK"); i++) {
                RunHarness.Result w = run(algo, g, ds, minSup, minWeight, budget, limitMs);
                if (w.partial()) status = w.status;
            }
            for (int i = 0; i < MEASURED; i++) {
                RunHarness.Result r = run(algo, g, ds, minSup, minWeight, budget, limitMs);
                if (r.partial()) {
                    nonOk++;
                    if (!status.equals("OOM")) status = r.status; // OOM outranks TO
                }
                measured.add(r);
            }
        }
        if (status.equals("OOM")) anyOom = true;
        report(algo, ds, minSup, minWeight, budget, limitMs, measured, status, nonOk);
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
                .put("mniIsoCallsMine", none ? "" : last.mniIsoCallsMine())
                .put("mniIsoCallsTotal", none ? "" : last.m.mniIsoCalls)
                .put("weightIsoCalls", none ? "" : last.m.weightIsoCalls)
                .put("tableSize", none ? "" : last.tableSize)
                .put("tableBuildCandidates", none ? "" : last.m.tableBuildCandidates)
                .put("tableBuildMniIsoCalls", none ? "" : last.m.tableBuildMniIsoCalls)
                .put("tableBuildWeightIsoCalls", none ? "" : last.m.tableBuildWeightIsoCalls)
                .put("budgetExceeded", none ? "" : last.m.budgetExceeded)
                .put("voter", none ? "" : last.cfg.voter.label())
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
