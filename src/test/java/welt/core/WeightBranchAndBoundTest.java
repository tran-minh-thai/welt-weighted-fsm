package welt.core;

import org.junit.jupiter.api.Test;
import welt.strategy.GraMiStrategy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The weight check answers the same with and without the weight branch-and-bound; without it the
 * search tests the bottleneck on complete embeddings and keeps searching when the test fails.
 */
class WeightBranchAndBoundTest {

    private static final int EL = 0;

    /** Star: centre (label 1) with three label-0 leaves, edge weights 30, 70, 90 in adjacency order. */
    private static LabeledWeightedGraph star() {
        LabeledWeightedGraph g = new LabeledWeightedGraph(4);
        g.setVertexLabel(0, 1);
        g.setVertexLabel(1, 0);
        g.setVertexLabel(2, 0);
        g.setVertexLabel(3, 0);
        g.addUndirectedEdge(0, 1, EL, 30.0);
        g.addUndirectedEdge(0, 2, EL, 70.0);
        g.addUndirectedEdge(0, 3, EL, 90.0);
        return g;
    }

    private static MniSupportCounter counter(LabeledWeightedGraph g, Metrics m, boolean bnb) {
        MniSupportCounter c = new MniSupportCounter(g, m);
        c.setWeightBranchAndBound(bnb);
        return c;
    }

    /** Path leaf-centre-leaf: W = 70; the first complete embedding found (30, 70) fails at 70. */
    @Test
    void handExampleBothSettings() {
        Pattern path = Pattern.of(new int[]{0, 1, 0}, new int[][]{{0, 1, EL}, {1, 2, EL}});
        Pattern edge = Pattern.singleEdge(0, 1, EL);
        for (boolean bnb : new boolean[]{true, false}) {
            MniSupportCounter c = counter(star(), new Metrics(), bnb);
            assertTrue(c.embedsWithMinWeight(path, 70.0), "bnb=" + bnb);
            assertFalse(c.embedsWithMinWeight(path, 71.0), "bnb=" + bnb);
            assertTrue(c.embedsWithMinWeight(edge, 90.0), "bnb=" + bnb);
            assertFalse(c.embedsWithMinWeight(edge, 90.5), "bnb=" + bnb);
            assertTrue(c.embedsWithMinWeight(path, 0.0), "bnb=" + bnb);
        }
    }

    /** Every frequent pattern of a real cell, several thresholds: same answers, more search without the bound. */
    @Test
    void sameAnswersOnRealPatterns() throws IOException {
        LabeledWeightedGraph g = new LgGraphReader().read(Path.of("datasets/email_eu.lg")).graph;
        List<MiningEngine.FrequentPattern> frequent = new MiningEngine(g, new Metrics()).mine(15, new GraMiStrategy());
        Metrics mOn = new Metrics(), mOff = new Metrics();
        MniSupportCounter on = counter(g, mOn, true), off = counter(g, mOff, false);
        int compared = 0, yes = 0;
        for (double w : new double[]{1, 100, 336, 1000}) {
            for (MiningEngine.FrequentPattern fp : frequent) {
                boolean a = on.embedsWithMinWeight(fp.pattern, w);
                assertEquals(a, off.embedsWithMinWeight(fp.pattern, w), CanonicalCode.of(fp.pattern) + " w=" + w);
                compared++;
                if (a) yes++;
            }
        }
        assertTrue(yes > 0 && yes < compared, "both answers occur: " + yes + " yes of " + compared);
        assertTrue(mOff.backtrackWeight > mOn.backtrackWeight,
                "without the bound the search visits more nodes: " + mOff.backtrackWeight + " vs " + mOn.backtrackWeight);
    }
}
