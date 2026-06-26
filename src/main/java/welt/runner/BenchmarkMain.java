package welt.runner;

import welt.core.GraphIndex;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.Metrics;
import welt.core.MiningEngine;
import welt.core.MniSupportCounter;
import welt.strategy.GraMiStrategy;
import welt.strategy.MiningStrategy;
import welt.strategy.OWGraMiStrategy;
import welt.strategy.WEGMStrategy;
import welt.strategy.WeLTStrategy;
import welt.strategy.WeightedLookupTable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Experimentally rigorous BENCHMARK HARNESS:
 * <ul>
 *   <li><b>Times multiple runs</b>: {@code WARMUP} warmup runs (discarded, to let the JIT
 *       warm up) + {@code MEASURED} measured runs, reporting the MEDIAN (more stable than
 *       the mean under GC/OS noise).</li>
 *   <li><b>Counting metrics printed once</b> (candidateCount/MINEmni/freq are DETERMINISTIC).</li>
 *   <li><b>TIME LIMIT</b> per run: exceeding it ⇒ marked <b>T.O.</b> (no unbounded waiting);
 *       covers both lookup-table construction (strategy counter) and MNI counting (engine counter).</li>
 * </ul>
 *
 * <p>{@code java welt.runner.BenchmarkMain <ds> <minSup> <minWeight> <budget> <limitMs> [warmup] [measured]}
 */
public final class BenchmarkMain {

    private static int WARMUP = 1;
    private static int MEASURED = 5;

    /** When the BENCH_CSV environment variable is set, also emit machine-readable CSV rows
     *  (prefixed "CSV,") so a driver script can aggregate results across many runs. */
    private static final boolean CSV = System.getenv("BENCH_CSV") != null;

    /**
     * Weight semantics of each strategy, recorded as a CSV column so result aggregation never
     * confuses non-comparable result sets:
     * <ul>
     *   <li>{@code none}   — GraMi: unweighted; its #FWS is the structural superset (reference).</li>
     *   <li>{@code edge}   — WEGM and WeLT: the SAME edge-bottleneck FWS, so their #FWS MUST be
     *       identical (a built-in correctness cross-check); their efficiency is what differs.</li>
     *   <li>{@code vertex} — OWGraMi: a DIFFERENT, vertex-bottleneck set; never compare its #FWS
     *       with the others — read its performance metrics only.</li>
     * </ul>
     */
    private static String weightModel(String algo) {
        switch (algo) {
            case "WEGM": case "WeLT": return "edge";
            case "OWGraMi":           return "vertex";
            default:                  return "none";
        }
    }

    private static void emitCsv(String ds, String algo, int minSup, double minWeight, long budget,
                                long limitMs, int freq, long cand, long mineMni, String time, String to) {
        if (CSV) {
            System.out.printf("CSV,%s,%s,%s,%d,%.1f,%d,%d,%d,%d,%d,%s,%s%n",
                    ds, algo, weightModel(algo), minSup, minWeight, budget, limitMs, freq, cand, mineMni, time, to);
        }
    }

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        int minSup = Integer.parseInt(args[1]);
        double minWeight = Double.parseDouble(args[2]);
        long budget = args.length > 3 ? Long.parseLong(args[3]) : 0;
        long limitMs = args.length > 4 ? Long.parseLong(args[4]) : 0; // 0 = no limit
        if (args.length > 5) WARMUP = Integer.parseInt(args[5]);
        if (args.length > 6) MEASURED = Integer.parseInt(args[6]);

        LabeledWeightedGraph g = new LgGraphReader().read(file).graph;
        String ds = file.getFileName().toString();
        System.out.printf("== Benchmark | %s | minSup=%d minWeight=%.1f budget=%s | limit=%s | warmup=%d measured=%d ==%n",
                ds, minSup, minWeight, budget == 0 ? "∞" : ("" + budget),
                limitMs == 0 ? "∞" : (limitMs + "ms"), WARMUP, MEASURED);
        System.out.printf("%-9s %-7s %6s %8s %10s %12s %6s%n",
                "algo", "model", "#FWS", "candMNI", "MINEmni", "time(ms)", "T.O.");
        if (CSV) System.out.println("CSV,dataset,algorithm,weightModel,minSup,minWeight,budget,limitMs,freq,candMNI,mineMNI,medianMs,timedOut");

        // Which strategies to run: BENCH_ALGOS (comma/space separated) overrides the default,
        // letting a scenario run just the comparable edge-weight set (GraMi,WEGM,WeLT) or only
        // the vertex-weight generality strategy (OWGraMi).
        String algosEnv = System.getenv("BENCH_ALGOS");
        String[] algos = (algosEnv == null || algosEnv.isBlank())
                ? new String[]{"GraMi", "WEGM", "WeLT", "OWGraMi"}
                : algosEnv.trim().split("[,\\s]+");
        for (String algo : algos) {
            benchmark(algo, g, ds, minSup, minWeight, budget, limitMs);
        }
    }

    private static void benchmark(String algo, LabeledWeightedGraph g, String ds,
                                  int minSup, double minWeight, long budget, long limitMs) {
        // Single probe run: if it times out immediately, report T.O. (avoids 6 runs × limit — saves time).
        Run probe = runOnce(algo, g, minSup, minWeight, budget, limitMs);
        if (probe.timedOut) {
            long pmni = probe.m.mniIsoCalls - probe.m.tableBuildMniIsoCalls;
            System.out.printf("%-9s %-7s %6d %8d %10d %12s %6s%n",
                    algo, weightModel(algo), probe.fps, probe.m.candidateCount, pmni, "T.O.(>" + limitMs + ")", "1/1");
            emitCsv(ds, algo, minSup, minWeight, budget, limitMs, probe.fps, probe.m.candidateCount, pmni, "TO", "1/1");
            return;
        }
        for (int i = 1; i < WARMUP; i++) runOnce(algo, g, minSup, minWeight, budget, limitMs);

        List<Long> times = new ArrayList<>();
        int toCount = 0;
        Run last = probe;
        for (int i = 0; i < MEASURED; i++) {
            Run r = runOnce(algo, g, minSup, minWeight, budget, limitMs);
            last = r;
            if (r.timedOut) toCount++;
            else times.add(r.timeMs);
        }
        long mineMni = last == null ? 0 : last.m.mniIsoCalls - last.m.tableBuildMniIsoCalls;
        String timeStr;
        if (times.isEmpty()) {
            timeStr = "T.O.(>" + limitMs + ")";
        } else {
            Collections.sort(times);
            timeStr = String.valueOf(times.get(times.size() / 2)); // median
        }
        String toStr = toCount > 0 ? (toCount + "/" + MEASURED) : "-";
        System.out.printf("%-9s %-7s %6d %8d %10d %12s %6s%n",
                algo, weightModel(algo), last.fps, last.m.candidateCount, mineMni, timeStr, toStr);
        emitCsv(ds, algo, minSup, minWeight, budget, limitMs, last.fps, last.m.candidateCount, mineMni, timeStr, toStr);
    }

    private static final class Run {
        final Metrics m;
        final long timeMs;
        final boolean timedOut;
        final int fps;
        Run(Metrics m, long timeMs, boolean timedOut, int fps) {
            this.m = m; this.timeMs = timeMs; this.timedOut = timedOut; this.fps = fps;
        }
    }

    private static Run runOnce(String algo, LabeledWeightedGraph g,
                               int minSup, double minWeight, long budget, long limitMs) {
        Metrics m = new Metrics();
        long deadline = limitMs > 0 ? System.nanoTime() + limitMs * 1_000_000L : Long.MAX_VALUE;
        GraphIndex index = new GraphIndex(g, minSup);
        MniSupportCounter counter = new MniSupportCounter(g, m); // strategy counter (table/weights)
        counter.setSearchBudget(budget);
        counter.setDeadline(deadline);
        MiningStrategy strategy;
        switch (algo) {
            case "GraMi": strategy = new GraMiStrategy(); break;
            case "WEGM": strategy = new WEGMStrategy(g, minWeight, counter, m); break;
            case "OWGraMi": strategy = new OWGraMiStrategy(g, minWeight, counter, m); break;
            case "WeLT": {
                WeightedLookupTable table = WeightedLookupTable.build(g, index, minSup, counter, m);
                strategy = new WeLTStrategy(g, minWeight, counter, table, m);
                break;
            }
            default: throw new IllegalArgumentException(algo);
        }
        MiningEngine engine = new MiningEngine(g, m);
        engine.setSearchBudget(budget);
        engine.setDeadline(deadline);
        long t0 = System.nanoTime();
        List<MiningEngine.FrequentPattern> fps = engine.mine(minSup, strategy);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        boolean to = engine.isTimedOut() || counter.isTimedOut() || (limitMs > 0 && ms > limitMs);
        return new Run(m, ms, to, fps.size());
    }
}
