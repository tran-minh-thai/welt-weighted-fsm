package welt.runner;

import welt.core.GraphIndex;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.Metrics;
import welt.core.MiningEngine;
import welt.core.MniSupportCounter;
import welt.strategy.GraMiStrategy;
import welt.strategy.MiningStrategy;
import welt.strategy.WeLTStrategy;
import welt.strategy.WeightedLookupTable;

import java.nio.file.Path;
import java.util.List;

/**
 * MILESTONE 2/3 — runs a single mining strategy on one dataset.
 *
 * Usage:
 *   java welt.runner.MineMain datasets/citeseer.lg 500 GraMi
 *   java welt.runner.MineMain datasets/citeseer.lg 300 WeLT 85   (minWeight=85)
 */
public final class MineMain {

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args.length > 0 ? args[0] : "datasets/citeseer.lg");
        int minSup = args.length > 1 ? Integer.parseInt(args[1]) : 500;
        String algo = args.length > 2 ? args[2] : "GraMi";
        double minWeight = args.length > 3 ? Double.parseDouble(args[3]) : Double.NaN;

        LgGraphReader reader = new LgGraphReader(); // default: unlabeled edges, ω = raw value
        LgGraphReader.Result res = reader.read(file);
        LabeledWeightedGraph g = res.graph;
        String dataset = file.getFileName().toString();

        Metrics m = new Metrics();
        MiningStrategy strategy;
        if ("GraMi".equalsIgnoreCase(algo)) {
            strategy = new GraMiStrategy();
        } else if ("WeLT".equalsIgnoreCase(algo)) {
            if (Double.isNaN(minWeight)) {
                throw new IllegalArgumentException("WeLT requires the minWeight parameter (4th argument).");
            }
            // preprocessing: index + shared counter + lookup table (k=2)
            GraphIndex index = new GraphIndex(g, minSup);
            MniSupportCounter counter = new MniSupportCounter(g, m);
            WeightedLookupTable table = WeightedLookupTable.build(g, index, minSup, counter, m);
            strategy = new WeLTStrategy(g, minWeight, counter, table, m);
            System.out.printf("k=2 lookup table: %d frequent 2-edge patterns; table-build iso-call=%d%n",
                    table.size(), m.tableBuildIsoCalls);
        } else {
            throw new IllegalArgumentException("Unsupported strategy: " + algo);
        }

        System.out.printf("== %s on %s | minSup=%d%s | %d vertices, %d edges ==%n",
                strategy.name(), dataset, minSup,
                Double.isNaN(minWeight) ? "" : (" | minWeight=" + minWeight),
                g.numVertices(), g.numEdges());

        MiningEngine engine = new MiningEngine(g, m);
        long t0 = System.nanoTime();
        List<MiningEngine.FrequentPattern> fps = engine.mine(minSup, strategy);
        m.timeMs = (System.nanoTime() - t0) / 1_000_000;
        m.sampleMemory();

        System.out.println("Frequent vertex labels + vertex counts: " + engine.frequentLabelCounts());
        System.out.println("Number of FWS patterns (≥1 edge): " + fps.size());
        if (m.prunedByStrategy > 0) {
            System.out.printf("Pruning: P1=%d, P2=%d (%d candidates in total skipped the MNI call)%n",
                    m.prunedByP1, m.prunedByP2, m.prunedByStrategy);
        }

        fps.sort((a, b) -> {
            int ca = a.pattern.edgeCount(), cb = b.pattern.edgeCount();
            if (ca != cb) return Integer.compare(ca, cb);
            return welt.core.CanonicalCode.of(a.pattern).compareTo(welt.core.CanonicalCode.of(b.pattern));
        });
        int i = 0;
        for (MiningEngine.FrequentPattern fp : fps) {
            System.out.printf("--- pattern %d (edges=%d) ---%n", i++, fp.pattern.edgeCount());
            System.out.print(fp.pattern.toLgString());
        }

        System.out.println();
        System.out.println(Metrics.csvHeader());
        System.out.println(m.csvRow(strategy.name(), dataset, minSup, minWeight, table_k(strategy)));
    }

    private static int table_k(MiningStrategy s) {
        return (s instanceof WeLTStrategy w) ? w.table().k() : 0;
    }
}
