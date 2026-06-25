package welt.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Index derived from the graph + support threshold, SHARED by {@link MiningEngine}
 * and {@link welt.strategy.WeightedLookupTable}: the frequent vertex labels, the edge
 * triples (labelA, edgeLabel, labelB) actually present in G, the set of edge labels,
 * and the list of distinct weights (descending) used for bottleneck computation.
 */
public final class GraphIndex {

    public final int[] labelCount;                 // labelCount[label]
    public final TreeSet<Integer> frequentLabels;  // labels with count >= minSup
    public final Set<Long> presentTriples;         // edge triples actually present
    public final TreeSet<Integer> edgeLabels;      // edge labels that occur
    public final double[] sortedDistinctWeightsDesc; // distinct weights, DESCENDING

    public GraphIndex(LabeledWeightedGraph g, int minSup) {
        int maxLabel = 0;
        for (int v = 0; v < g.numVertices(); v++) maxLabel = Math.max(maxLabel, g.vertexLabel(v));
        labelCount = new int[maxLabel + 1];
        for (int v = 0; v < g.numVertices(); v++) labelCount[g.vertexLabel(v)]++;

        frequentLabels = new TreeSet<>();
        for (int l = 0; l <= maxLabel; l++) if (labelCount[l] >= minSup) frequentLabels.add(l);

        presentTriples = new HashSet<>();
        edgeLabels = new TreeSet<>();
        TreeSet<Double> weights = new TreeSet<>();
        for (int u = 0; u < g.numVertices(); u++) {
            int lu = g.vertexLabel(u);
            for (LabeledWeightedGraph.Adjacency a : g.neighbors(u)) {
                if (u < a.to) {
                    presentTriples.add(tripleKey(lu, g.vertexLabel(a.to), a.edgeLabel));
                    edgeLabels.add(a.edgeLabel);
                    weights.add(a.weight);
                }
            }
        }
        sortedDistinctWeightsDesc = new double[weights.size()];
        int i = 0;
        for (double w : weights.descendingSet()) sortedDistinctWeightsDesc[i++] = w;
    }

    public boolean triplePresent(int la, int lb, int el) {
        return presentTriples.contains(tripleKey(la, lb, el));
    }

    public static long tripleKey(int la, int lb, int el) {
        int lo = Math.min(la, lb), hi = Math.max(la, lb);
        return (((long) lo) << 42) | (((long) hi) << 21) | (el & 0x1FFFFFL);
    }

    @Override
    public String toString() {
        return "GraphIndex(freqLabels=" + frequentLabels + ", triples=" + presentTriples.size()
                + ", edgeLabels=" + edgeLabels + ", distinctWeights=" + sortedDistinctWeightsDesc.length + ")";
    }
}
