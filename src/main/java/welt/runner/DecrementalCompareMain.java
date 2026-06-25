package welt.runner;

import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.Metrics;
import welt.core.MiningEngine;
import welt.strategy.GraMiStrategy;

import java.nio.file.Path;
import java.util.List;

/**
 * Isolates the contribution of parent→child DOMAIN INHERITANCE (decremental). Runs GraMi
 * (unweighted) with inheritance OFF then ON; prints time + isoCall. The result set must be
 * IDENTICAL. The benefit is clear on large graphs (mico, |V|=100K) where scanning buildDomain
 * over the whole G is very expensive.
 *
 * <p>{@code java welt.runner.DecrementalCompareMain datasets/mico.lg 9000}
 */
public final class DecrementalCompareMain {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        int minSup = Integer.parseInt(args[1]);
        LabeledWeightedGraph g = new LgGraphReader().read(file).graph;
        System.out.printf("== Domain inheritance (decremental) | %s | minSup=%d | %d vertices %d edges ==%n",
                file.getFileName(), minSup, g.numVertices(), g.numEdges());
        System.out.printf("%-16s %6s %10s %9s%n", "inheritance", "#freq", "isoCall", "time(ms)");
        run(g, minSup, false);
        run(g, minSup, true);
    }

    private static void run(LabeledWeightedGraph g, int minSup, boolean inherit) {
        Metrics m = new Metrics();
        MiningEngine engine = new MiningEngine(g, m);
        engine.setDomainInheritance(inherit);
        long t0 = System.nanoTime();
        List<MiningEngine.FrequentPattern> fps = engine.mine(minSup, new GraMiStrategy());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("%-16s %6d %10d %9d%n", inherit ? "ON" : "OFF", fps.size(), m.isoCallCount, ms);
    }
}
