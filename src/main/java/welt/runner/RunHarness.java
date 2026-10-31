package welt.runner;

import welt.core.GraphIndex;
import welt.core.LabeledWeightedGraph;
import welt.core.Metrics;
import welt.core.MiningEngine;
import welt.core.MniSupportCounter;
import welt.core.PivotVoter;
import welt.strategy.EdgeTypeBoundStrategy;
import welt.strategy.GraMiFilterStrategy;
import welt.strategy.GraMiStrategy;
import welt.strategy.MiningStrategy;
import welt.strategy.OWGraMiStrategy;
import welt.strategy.WEGMStrategy;
import welt.strategy.WeLTStrategy;
import welt.strategy.WeightedLookupTable;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One timed mining run, shared by every benchmark runner so that all of them measure the
 * same thing.
 *
 * <p><b>What is inside the clock.</b> Reading the {@code .lg} file is outside (identical for
 * every algorithm). Everything the algorithm needs after that is inside, in this order:
 * <ol>
 *   <li>{@code indexMs}: the {@link GraphIndex} (frequent labels, edge triples, distinct
 *       weights), built once and shared by the lookup table and the engine;</li>
 *   <li>{@code prepMs}: the strategy's own preprocessing — for WeLT the lookup table
 *       ({@code tableBuildMs}), for WEGM/OWGraMi their per-type weight maps;</li>
 *   <li>{@code mineMs}: the level-wise mining.</li>
 * </ol>
 * {@code totalMs} spans all three and is the reported runtime. The peak heap is reset
 * right before the clock starts, after a {@code System.gc()} so that garbage left by a
 * previous run in the same JVM does not count towards this run. The counters, engine and
 * strategy are constructed inside the clock (their constructors are trivial).
 *
 * <p><b>Status.</b> {@code OK}, {@code TO} (the time limit was reached; the deadline is
 * checked inside MNI counting, weight checks and backtracking) or {@code OOM}
 * ({@link OutOfMemoryError} caught here). For TO and OOM the counting metrics are those
 * reached at the stop ({@link Result#partial()}).
 */
public final class RunHarness {

    private RunHarness() {
    }

    /** What to run. */
    public static final class Config {
        public final String algo;
        public final int minSup;
        public final double minWeight;
        public long budget = 0;   // 0 = exact mode
        public long limitMs = 0;  // 0 = no time limit
        /** Voting for the MNI variable order and the weight-check pivot (both counters). */
        public PivotVoter voter = PivotVoter.DEGREE_ONLY;
        /** Weight-aware matching order inside the edge-weight check. */
        public boolean weightOrder = false;
        /** Ablation flags (WeLT): lookup-table filters P1, P2 and weight-gated extension. */
        public boolean p1 = true, p2 = true, wext = true;
        /** The k = 1 part of P2 (heaviest edge per edge type); acts only when p2 is on. */
        public boolean p2single = true;
        /** Weight branch-and-bound inside the weight check (off = test complete embeddings only). */
        public boolean bnb = true;
        /** WeLT: the miner takes the 1- and 2-edge support results of the table build. */
        public boolean tableReuse = true;
        /** WeLT: the table stores the weight test at minWeight instead of exact MaxW values. */
        public boolean tableThreshold = true;
        /**
         * WeLT only: build the lookup table. Off, no table is built and P1/P2 give way to the
         * per-edge-type bound on every edge of every candidate (EdgeTypeBoundStrategy), with the
         * voter, the weight-aware order, the weight-gated extension and the branch-and-bound of
         * this configuration.
         */
        public boolean tableBuild = true;
        /** Ablation flags (every algorithm): P3 closure, and P3 against R instead of F. */
        public boolean p3 = true, p3AgainstResults = false;
        /** Record per-phase times of the mining phase (ablation runs only). */
        public boolean phaseTiming = false;
        /** Untimed live-heap run: forced collections as the retained data grows (see Metrics). */
        public boolean liveHeap = false;

        public Config(String algo, int minSup, double minWeight) {
            this.algo = algo;
            this.minSup = minSup;
            this.minWeight = minWeight;
        }
    }

    /** What one run produced. Counting metrics live in {@link #m}. */
    public static final class Result {
        public final Config cfg;
        public final Metrics m = new Metrics();
        public List<MiningEngine.FrequentPattern> patterns = List.of();
        public double indexMs, prepMs, tableBuildMs, mineMs, totalMs;
        public int tableSize = 0;
        public double peakMB;
        public boolean timedOut;
        /** OK (completed), TO (time limit reached) or OOM (OutOfMemoryError). */
        public String status = "OK";

        /** {@code true} when the run stopped early: counts are those reached at the stop. */
        public boolean partial() {
            return !status.equals("OK");
        }

        Result(Config cfg) {
            this.cfg = cfg;
        }

        /** MNI iso-calls of the mining phase only (excluding the lookup-table construction). */
        public long mniIsoCallsMine() {
            return m.mniIsoCalls - m.tableBuildMniIsoCalls;
        }

        /** Patterns whose MNI was evaluated: mining phase plus lookup-table construction. */
        public long candidatesTotal() {
            return m.candidateCount + m.tableBuildCandidates;
        }
    }

    /** Reset the peak usage of every heap pool. */
    public static void resetPeakHeap() {
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans())
            if (p.getType() == MemoryType.HEAP) p.resetPeakUsage();
    }

    /** Peak heap used (MB) since the last reset, summed over the heap pools. */
    public static double peakHeapMB() {
        long peak = 0;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans())
            if (p.getType() == MemoryType.HEAP && p.getPeakUsage() != null)
                peak += p.getPeakUsage().getUsed();
        return peak / (1024.0 * 1024.0);
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }

    /** Run {@code cfg} once on {@code g}. */
    public static Result runOnce(LabeledWeightedGraph g, Config cfg) {
        Result r = new Result(cfg);
        r.m.liveHeapGraphMB = Metrics.usedHeapAfterGcMB(); // the collection that precedes every run
        r.m.liveHeap = cfg.liveHeap;
        resetPeakHeap();
        long t0 = System.nanoTime();
        try {
            mineTimed(g, cfg, r, t0);
        } catch (OutOfMemoryError oom) {
            // Every structure of the run (counters, engine, strategy, partial result list) is
            // created inside mineTimed and unreachable once the error leaves it, so the heap is
            // available again for reporting. The incremental counters in r.m keep the values
            // reached when the error occurred.
            r.patterns = List.of();
            r.indexMs = r.prepMs = r.tableBuildMs = r.mineMs = Double.NaN; // not measured: phase unknown
            r.totalMs = ms(System.nanoTime() - t0);
            r.peakMB = peakHeapMB();
            r.status = "OOM";
            return r;
        }
        if (r.timedOut) r.status = "TO";
        return r;
    }

    /** WEGM mines the weight-labeled copy of the graph (see WEGMStrategy); every other algorithm the input graph. */
    public static boolean usesWeightLabels(String algo) {
        return algo.equals("WEGM");
    }

    private static void mineTimed(LabeledWeightedGraph input, Config cfg, Result r, long t0) {
        Metrics m = r.m;
        m.phaseTiming = cfg.phaseTiming;
        // WEGM preprocessing that belongs to the algorithm: relabel edges by integer weight.
        LabeledWeightedGraph g = usesWeightLabels(cfg.algo) ? WEGMStrategy.weightLabeledGraph(input) : input;
        long tRelabel = System.nanoTime();
        long deadline = cfg.limitMs > 0 ? t0 + cfg.limitMs * 1_000_000L : Long.MAX_VALUE;
        MniSupportCounter counter = new MniSupportCounter(g, m); // strategy counter (table / weight checks)
        counter.setSearchBudget(cfg.budget);
        counter.setDeadline(deadline);
        counter.setVoter(cfg.voter);
        counter.setWeightAwareOrdering(cfg.weightOrder);
        counter.setWeightBranchAndBound(cfg.bnb);
        MiningEngine engine = new MiningEngine(g, m);    // engine counter (MNI of the mining phase)
        engine.setSearchBudget(cfg.budget);
        engine.setDeadline(deadline);
        engine.setVoter(cfg.voter);
        engine.setAprioriPruning(cfg.p3);
        engine.setP3AgainstResults(cfg.p3AgainstResults);

        long tIndex = System.nanoTime();
        GraphIndex index = new GraphIndex(g, cfg.minSup);
        long t1 = System.nanoTime();

        MiningStrategy strategy;
        switch (cfg.algo) {
            case "GraMi": strategy = new GraMiStrategy(); break;
            case "GraMiFilter": strategy = new GraMiFilterStrategy(cfg.minWeight, counter); break;
            case "EdgeTypeBound": strategy = new EdgeTypeBoundStrategy(g, cfg.minWeight, counter, m, true); break;
            case "EdgeTypeBound-ungated": strategy = new EdgeTypeBoundStrategy(g, cfg.minWeight, counter, m, false); break;
            // WeLT with the lookup table replaced by the per-edge-type bound: the noTableBuild ablation
            // variant, run as an algorithm of its own (no phase timers)
            case "WeLT-noTableBuild": strategy = new EdgeTypeBoundStrategy(g, cfg.minWeight, counter, m, cfg.wext); break;
            case "WEGM": strategy = new WEGMStrategy(cfg.minWeight, m); break;
            case "OWGraMi": strategy = new OWGraMiStrategy(g, cfg.minWeight, counter, m); break;
            case "WeLT": {
                if (!cfg.tableBuild) {
                    strategy = new EdgeTypeBoundStrategy(g, cfg.minWeight, counter, m, cfg.wext);
                    break;
                }
                long tb = System.nanoTime();
                WeightedLookupTable.Options opt = new WeightedLookupTable.Options();
                opt.keepSupports = cfg.tableReuse;
                if (cfg.tableThreshold) opt.threshold = cfg.minWeight;
                WeightedLookupTable table = WeightedLookupTable.build(g, index, cfg.minSup, counter, m, opt);
                r.tableBuildMs = ms(System.nanoTime() - tb);
                r.tableSize = table.size();
                WeLTStrategy welt = new WeLTStrategy(g, cfg.minWeight, counter, table, m);
                welt.setFilters(cfg.p1, cfg.p2);
                welt.setWeightExtension(cfg.wext);
                welt.setSingleEdgeBound(cfg.p2single);
                welt.setReuseSupports(cfg.tableReuse);
                strategy = welt;
                break;
            }
            default: throw new IllegalArgumentException("unknown algorithm: " + cfg.algo);
        }
        long t2 = System.nanoTime();

        r.patterns = engine.mine(cfg.minSup, strategy, index);
        long t3 = System.nanoTime();

        r.indexMs = ms(t1 - tIndex);
        r.prepMs = ms(tRelabel - t0) + ms(tIndex - tRelabel) + ms(t2 - t1); // relabeling (WEGM) + counters + strategy
        r.mineMs = ms(t3 - t2);
        r.totalMs = ms(t3 - t0);
        r.peakMB = peakHeapMB();
        r.timedOut = engine.isTimedOut() || counter.isTimedOut()
                || (cfg.limitMs > 0 && r.totalMs > cfg.limitMs);
    }

    /** Ordered CSV row builder: the header is derived from the keys, never typed twice. */
    public static final class Row {
        private final Map<String, String> cells = new LinkedHashMap<>();

        public Row put(String column, Object value) {
            if (cells.containsKey(column)) throw new IllegalStateException("duplicate column " + column);
            String text;
            if (value instanceof Double) {
                double d = (Double) value;
                text = Double.isNaN(d) ? "" : String.format(Locale.ROOT, "%.3f", d); // NaN = not measured
            } else {
                text = String.valueOf(value);
            }
            cells.put(column, text);
            return this;
        }

        public String header() {
            return String.join(",", cells.keySet());
        }

        public String values() {
            for (String v : cells.values())
                if (v.contains(",")) throw new IllegalStateException("comma inside a CSV value: " + v);
            return String.join(",", cells.values());
        }
    }
}
