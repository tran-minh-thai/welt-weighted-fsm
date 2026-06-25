package welt.runner;

import welt.core.EdgeLabelAssigner;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.Metrics;
import welt.core.MiningEngine;
import welt.core.WeightAssigner;
import welt.strategy.GraMiStrategy;

import java.nio.file.Path;
import java.util.List;

/**
 * Feasibility probe: mining MiCo USING the real EDGE LABELS (IDENTITY) instead of treating
 * it as unlabeled. Expectation: orders of magnitude faster because edge labels constrain
 * patterns strongly.
 *
 * <p>{@code java welt.runner.MicoLabeledProbe datasets/mico.lg 5000}
 */
public final class MicoLabeledProbe {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        int minSup = Integer.parseInt(args[1]);
        // edge label = raw value (number of co-authors); weight = same value
        LgGraphReader reader = new LgGraphReader(WeightAssigner.IDENTITY, EdgeLabelAssigner.IDENTITY);
        LabeledWeightedGraph g = reader.read(file).graph;
        Metrics m = new Metrics();
        long t0 = System.nanoTime();
        List<MiningEngine.FrequentPattern> fps = new MiningEngine(g, m).mine(minSup, new GraMiStrategy());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("MiCo LABELED | minSup=%d | %d vertices %d edges | freq=%d cand=%d mniIso=%d time=%dms%n",
                minSup, g.numVertices(), g.numEdges(), fps.size(), m.candidateCount, m.mniIsoCalls, ms);
    }
}
