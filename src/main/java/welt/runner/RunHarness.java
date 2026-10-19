package welt.runner;

import welt.core.GraphIndex;
import welt.core.LabeledWeightedGraph;
import welt.core.Metrics;
import welt.core.MiningEngine;
import welt.core.MniSupportCounter;
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
 * previous run in the same JVM does not count towards this run.
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
        Metrics m = r.m;
        MniSupportCounter counter = new MniSupportCounter(g, m); // strategy counter (table / weight checks)
        counter.setSearchBudget(cfg.budget);
        MiningEngine engine = new MiningEngine(g, m);
        engine.setSearchBudget(cfg.budget);

        System.gc();
        resetPeakHeap();
        long t0 = System.nanoTime();
        long deadline = cfg.limitMs > 0 ? t0 + cfg.limitMs * 1_000_000L : Long.MAX_VALUE;
        counter.setDeadline(deadline);
        engine.setDeadline(deadline);

        GraphIndex index = new GraphIndex(g, cfg.minSup);
        long t1 = System.nanoTime();

        MiningStrategy strategy;
        switch (cfg.algo) {
            case "GraMi": strategy = new GraMiStrategy(); break;
            case "WEGM": strategy = new WEGMStrategy(g, cfg.minWeight, counter, m); break;
            case "OWGraMi": strategy = new OWGraMiStrategy(g, cfg.minWeight, counter, m); break;
            case "WeLT": {
                long tb = System.nanoTime();
                WeightedLookupTable table = WeightedLookupTable.build(g, index, cfg.minSup, counter, m);
                r.tableBuildMs = ms(System.nanoTime() - tb);
                r.tableSize = table.size();
                strategy = new WeLTStrategy(g, cfg.minWeight, counter, table, m);
                break;
            }
            default: throw new IllegalArgumentException("unknown algorithm: " + cfg.algo);
        }
        long t2 = System.nanoTime();

        r.patterns = engine.mine(cfg.minSup, strategy, index);
        long t3 = System.nanoTime();

        r.indexMs = ms(t1 - t0);
        r.prepMs = ms(t2 - t1);
        r.mineMs = ms(t3 - t2);
        r.totalMs = ms(t3 - t0);
        r.peakMB = peakHeapMB();
        r.timedOut = engine.isTimedOut() || counter.isTimedOut()
                || (cfg.limitMs > 0 && r.totalMs > cfg.limitMs);
        return r;
    }

    /** Ordered CSV row builder: the header is derived from the keys, never typed twice. */
    public static final class Row {
        private final Map<String, String> cells = new LinkedHashMap<>();

        public Row put(String column, Object value) {
            if (cells.containsKey(column)) throw new IllegalStateException("duplicate column " + column);
            cells.put(column, value instanceof Double ? String.format(Locale.ROOT, "%.3f", (Double) value) : String.valueOf(value));
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
