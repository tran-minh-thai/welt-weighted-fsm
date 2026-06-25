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
import java.util.List;

/**
 * MILESTONE 4 — runs all four algorithms on the same dataset and prints a comparison CSV table.
 *
 * <p>Usage:
 * <pre>
 *   java welt.runner.CompareMain datasets/citeseer.lg 300 85
 *   # args: &lt;dataset&gt; &lt;minSup&gt; &lt;minWeight&gt;
 * </pre>
 *
 * <p><b>Comparison note:</b> OWGraMi's {@code frequentCount} column reflects VERTEX
 * weights, which is entirely different from the other three algorithms (EDGE weights) —
 * do NOT use it for accuracy comparison; only use the performance columns (candidateCount,
 * isoCallCount, timeMs, peakMemMB) for a direct comparison against GraMi/WEGM/WeLT.
 */
public final class CompareMain {

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args.length > 0 ? args[0] : "datasets/citeseer.lg");
        int minSup = args.length > 1 ? Integer.parseInt(args[1]) : 400;
        double minWeight = args.length > 2 ? Double.parseDouble(args[2]) : 85.0;
        long budget = args.length > 3 ? Long.parseLong(args[3]) : 0; // 0 = ∞ (exact)

        System.out.printf("== Comparison of 4 algorithms | dataset=%s | minSup=%d | minWeight=%.1f | budget=%s ==%n",
                file.getFileName(), minSup, minWeight, budget == 0 ? "∞(exact)" : String.valueOf(budget));

        // Read the graph (vertex weights assigned for OWGraMi)
        LgGraphReader reader = new LgGraphReader(); // default: AVERAGE_INCIDENT_EDGE for vw
        LgGraphReader.Result res = reader.read(file);
        LabeledWeightedGraph g = res.graph;
        String dataset = file.getFileName().toString();
        System.out.printf("Graph: %d vertices, %d edges; edge weight [%.2f, %.2f]%n",
                g.numVertices(), g.numEdges(), res.stats.minWeight, res.stats.maxWeight);

        // Shared index (GraphIndex, MniSupportCounter)
        GraphIndex index = new GraphIndex(g, minSup);

        List<String> csvRows = new ArrayList<>();
        csvRows.add(Metrics.csvHeader());

        // ---- 1. GraMi (unweighted) ----
        {
            Metrics m = new Metrics();
            MiningStrategy strategy = new GraMiStrategy();
            MiningEngine engine = new MiningEngine(g, m);
            engine.setSearchBudget(budget);
            long t0 = System.nanoTime();
            List<MiningEngine.FrequentPattern> fps = engine.mine(minSup, strategy);
            m.timeMs = (System.nanoTime() - t0) / 1_000_000;
            m.sampleMemory();
            System.out.printf("[GraMi]    freq=%d, cand=%d, iso=%d, time=%dms%n",
                    fps.size(), m.candidateCount, m.isoCallCount, m.timeMs);
            csvRows.add(m.csvRow("GraMi", dataset, minSup, Double.NaN, 0));
        }

        // ---- 2. WEGM (edge weights, no lookup table) ----
        {
            Metrics m = new Metrics();
            MniSupportCounter counter = new MniSupportCounter(g, m);
            counter.setSearchBudget(budget);
            WEGMStrategy strategy = new WEGMStrategy(g, minWeight, counter, m);
            MiningEngine engine = new MiningEngine(g, m);
            engine.setSearchBudget(budget);
            long t0 = System.nanoTime();
            List<MiningEngine.FrequentPattern> fps = engine.mine(minSup, strategy);
            m.timeMs = (System.nanoTime() - t0) / 1_000_000;
            m.sampleMemory();
            System.out.printf("[WEGM]     freq=%d, cand=%d, iso=%d, P2=%d, time=%dms%n",
                    fps.size(), m.candidateCount, m.isoCallCount, m.prunedByP2, m.timeMs);
            csvRows.add(m.csvRow("WEGM", dataset, minSup, minWeight, 0));
        }

        // ---- 3. WeLT (edge weights + F_2 lookup table) ----
        {
            Metrics m = new Metrics();
            MniSupportCounter counter = new MniSupportCounter(g, m);
            counter.setSearchBudget(budget);
            WeightedLookupTable table = WeightedLookupTable.build(g, index, minSup, counter, m);
            WeLTStrategy strategy = new WeLTStrategy(g, minWeight, counter, table, m);
            System.out.printf("[WeLT]     k=2 table: %d patterns; table-build iso=%d%n",
                    table.size(), m.tableBuildIsoCalls);
            MiningEngine engine = new MiningEngine(g, m);
            engine.setSearchBudget(budget);
            long t0 = System.nanoTime();
            List<MiningEngine.FrequentPattern> fps = engine.mine(minSup, strategy);
            m.timeMs = (System.nanoTime() - t0) / 1_000_000;
            m.sampleMemory();
            System.out.printf("[WeLT]     freq=%d, cand=%d, iso=%d, P1=%d, P2=%d, time=%dms%n",
                    fps.size(), m.candidateCount, m.isoCallCount,
                    m.prunedByP1, m.prunedByP2, m.timeMs);
            csvRows.add(m.csvRow("WeLT", dataset, minSup, minWeight, table.k()));
        }

        // ---- 4. OWGraMi (VERTEX weights — DIFFERENT result set, performance comparison only) ----
        {
            Metrics m = new Metrics();
            MniSupportCounter counter = new MniSupportCounter(g, m);
            counter.setSearchBudget(budget);
            OWGraMiStrategy strategy = new OWGraMiStrategy(g, minWeight, counter, m);
            System.out.printf("[OWGraMi]  'vertex-heavy' edge types: %d%n",
                    strategy.weightyTripleCount());
            MiningEngine engine = new MiningEngine(g, m);
            engine.setSearchBudget(budget);
            long t0 = System.nanoTime();
            List<MiningEngine.FrequentPattern> fps = engine.mine(minSup, strategy);
            m.timeMs = (System.nanoTime() - t0) / 1_000_000;
            m.sampleMemory();
            System.out.printf("[OWGraMi]  freq=%d (VERTEX≠EDGE), cand=%d, iso=%d, prune=%d, time=%dms%n",
                    fps.size(), m.candidateCount, m.isoCallCount, m.prunedByStrategy, m.timeMs);
            csvRows.add(m.csvRow("OWGraMi", dataset, minSup, minWeight, 0));
        }

        System.out.println();
        System.out.println("=== Comparison CSV (NOTE: OWGraMi's frequentCount uses VERTEX weights) ===");
        for (String row : csvRows) System.out.println(row);
    }
}
