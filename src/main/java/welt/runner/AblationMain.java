package welt.runner;

import welt.core.GraphIndex;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.Metrics;
import welt.core.MiningEngine;
import welt.core.MniSupportCounter;
import welt.core.PivotVoter;
import welt.strategy.WeLTStrategy;
import welt.strategy.WeightedLookupTable;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Path;
import java.util.List;

/**
 * ABLATION STUDY (RQ4): the per-component contribution of WeLT on one
 * {@code (dataset, minSup, minWeight)} configuration. Starting from the full algorithm, each
 * variant disables exactly ONE component while holding the rest fixed:
 * <ul>
 *   <li>{@code no-table}  — the lookup-table double filter (P1/P2) is turned off, so every
 *       generated candidate reaches the MNI count (isolates the table's pruning power);</li>
 *   <li>{@code no-vote}   — multi-criteria pivot voting falls back to domain-only ordering
 *       (isolates the voting signal's effect on the search-tree size);</li>
 *   <li>{@code no-worder} — the weight-aware matching order inside the weight embedding is
 *       turned off (isolates that ordering's effect on the per-check cost).</li>
 * </ul>
 *
 * <p>Every variant must return the SAME number of frequent weighted subgraphs (a correctness
 * invariant — the components change performance only, not the result). The differing metrics
 * (candidate count, MNI iso-calls, backtrack nodes, time, peak memory) quantify each
 * component's contribution.
 *
 * <p>Usage: {@code java welt.runner.AblationMain <ds> <minSup> <minWeight> [limitMs]}. With the
 * {@code ABLATION_CSV} environment variable set, also emit machine-readable "CSV," rows.
 */
public final class AblationMain {

    private static final boolean CSV = System.getenv("ABLATION_CSV") != null;
    private static final String[] VARIANTS = {"WeLT-full", "no-table", "no-vote", "no-worder"};

    private static void resetPeakHeap() {
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans())
            if (p.getType() == MemoryType.HEAP) p.resetPeakUsage();
    }

    private static double peakHeapMB() {
        long peak = 0;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans())
            if (p.getType() == MemoryType.HEAP && p.getPeakUsage() != null)
                peak += p.getPeakUsage().getUsed();
        return peak / (1024.0 * 1024.0);
    }

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args.length > 0 ? args[0] : "datasets/citeseer.lg");
        int minSup = args.length > 1 ? Integer.parseInt(args[1]) : 250;
        double minWeight = args.length > 2 ? Double.parseDouble(args[2]) : 96.0;
        long limitMs = args.length > 3 ? Long.parseLong(args[3]) : 0; // 0 = no limit

        LabeledWeightedGraph g = new LgGraphReader().read(file).graph;
        String ds = file.getFileName().toString();
        System.out.printf("== Ablation (RQ4) | %s | minSup=%d minWeight=%.1f | limit=%s ==%n",
                ds, minSup, minWeight, limitMs == 0 ? "∞" : (limitMs + "ms"));
        System.out.printf("%-10s %6s %9s %10s %10s %10s %8s %5s%n",
                "variant", "#FWS", "candMNI", "MINEmni", "backtrack", "time(ms)", "memMB", "T.O.");
        if (CSV) System.out.println("CSV,dataset,variant,minSup,minWeight,freq,candMNI,mineMNI,backtrack,timeMs,peakMemMB,timedOut");

        long refFws = -1;
        for (String variant : VARIANTS) {
            Metrics m = new Metrics();
            long deadline = limitMs > 0 ? System.nanoTime() + limitMs * 1_000_000L : Long.MAX_VALUE;
            GraphIndex index = new GraphIndex(g, minSup);

            // Full configuration; each variant disables exactly one component.
            PivotVoter voter = variant.equals("no-vote") ? PivotVoter.DOMAIN_ONLY : PivotVoter.WELT_DEFAULT;
            boolean weightOrder = !variant.equals("no-worder");

            MniSupportCounter counter = new MniSupportCounter(g, m);
            counter.setDeadline(deadline);
            counter.setVoter(voter);
            counter.setWeightAwareOrdering(weightOrder);
            WeightedLookupTable table = WeightedLookupTable.build(g, index, minSup, counter, m);
            WeLTStrategy strat = new WeLTStrategy(g, minWeight, counter, table, m);
            strat.setDoubleFilter(!variant.equals("no-table"));
            MiningEngine engine = new MiningEngine(g, m);
            engine.setVoter(voter);
            engine.setDeadline(deadline);

            resetPeakHeap();
            long t0 = System.nanoTime();
            List<MiningEngine.FrequentPattern> fps = engine.mine(minSup, strat);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            double peak = peakHeapMB();
            boolean to = engine.isTimedOut() || counter.isTimedOut();
            long mineMni = m.mniIsoCalls - m.tableBuildMniIsoCalls;
            String toStr = to ? "1/1" : "-";
            String timeStr = to ? "T.O." : ("" + ms);
            System.out.printf("%-10s %6d %9d %10d %10d %10s %8.1f %5s%n",
                    variant, fps.size(), m.candidateCount, mineMni, m.backtrackNodes, timeStr, peak, toStr);
            if (CSV) System.out.printf("CSV,%s,%s,%d,%.1f,%d,%d,%d,%d,%s,%.1f,%s%n",
                    ds, variant, minSup, minWeight, fps.size(), m.candidateCount, mineMni, m.backtrackNodes,
                    to ? "TO" : ("" + ms), peak, toStr);

            if (refFws < 0) {
                refFws = fps.size();
            } else if (!to && refFws != fps.size()) {
                System.out.println("  !! WARNING: #FWS differs from the full configuration — correctness invariant violated!");
            }
        }
        System.out.println("(Every variant that completes MUST share the same #FWS — the components change performance only.)");
    }
}
