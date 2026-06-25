package welt.strategy;

import welt.core.LabeledWeightedGraph;
import welt.core.Metrics;
import welt.core.MniSupportCounter;
import welt.core.Pattern;

import java.util.HashSet;
import java.util.Set;

/**
 * OWGraMi — VERTEX-weighted baseline, a reimplementation from the paper's
 * description (no original source code). Uses a VERTEX bottleneck instead of edges:
 * W_v(S) = max over occurrences of (min assigned vertex weight).
 *
 * <p><b>Note:</b> This is a REIMPLEMENTATION from the paper's description. Vertex
 * weights are DERIVED from the weights of incident edges (averaged by default,
 * {@link welt.core.VertexWeightAssigner#AVERAGE_INCIDENT_EDGE}), since GraMi's .lg
 * files have no vertex-weight field. State this explicitly in the report.
 *
 * <p><b>Comparison note:</b> The FWS result set of OWGraMi (vertex weights) is NOT
 * comparable with GraMi/WEGM/WeLT (edge weights) — the definition of W(S) is
 * entirely different. Compare only the PERFORMANCE METRICS ({@code candidateCount},
 * {@code isoCallCount}, {@code timeMs}) on the same dataset/minSup; do not compare
 * {@code frequentCount}.
 *
 * <p>Simple pre-prune: each edge type (la,el,lb) in the pattern must have at least
 * one edge in G whose two endpoints both have vertexWeight ≥ minWeight. Otherwise no
 * "vertex-heavy" embedding can exist → W_v(S) &lt; minWeight → prune.
 */
public final class OWGraMiStrategy implements MiningStrategy {

    private final double minWeight;
    private final MniSupportCounter counter;
    private final Metrics metrics;
    /**
     * Set of edge types in G whose BOTH endpoints have vertexWeight ≥ minWeight.
     * Key: (min_label << 42 | max_label << 21 | edge_label) — same encoding as GraphIndex.
     */
    private final Set<Long> weightyTriples;

    public OWGraMiStrategy(LabeledWeightedGraph g, double minWeight,
                           MniSupportCounter counter, Metrics metrics) {
        this.minWeight = minWeight;
        this.counter = counter;
        this.metrics = metrics;
        this.weightyTriples = buildWeightyTriples(g, minWeight);
    }

    private static Set<Long> buildWeightyTriples(LabeledWeightedGraph g, double minVW) {
        Set<Long> s = new HashSet<>();
        for (int u = 0; u < g.numVertices(); u++) {
            if (g.vertexWeight(u) < minVW) continue;
            int lu = g.vertexLabel(u);
            for (LabeledWeightedGraph.Adjacency adj : g.neighbors(u)) {
                if (adj.to <= u) continue;
                if (g.vertexWeight(adj.to) < minVW) continue;
                int lv = g.vertexLabel(adj.to);
                int la = Math.min(lu, lv), lb = Math.max(lu, lv);
                s.add(((long) la << 42) | ((long) lb << 21) | adj.edgeLabel);
            }
        }
        return s;
    }

    @Override
    public String name() {
        return "OWGraMi";
    }

    /**
     * Single-edge vertex-weight pre-filter: for each edge type (la,el,lb) in the
     * pattern there must exist at least one edge in G whose two endpoints both have
     * vertexWeight ≥ minWeight. Otherwise → no "vertex-heavy" embedding is possible
     * → W_v(S) &lt; minWeight.
     */
    @Override
    public boolean prePrune(Pattern s) {
        for (int[] e : s.edges()) {
            int la = Math.min(s.nodeLabel(e[0]), s.nodeLabel(e[1]));
            int lb = Math.max(s.nodeLabel(e[0]), s.nodeLabel(e[1]));
            long key = ((long) la << 42) | ((long) lb << 21) | e[2];
            if (!weightyTriples.contains(key)) {
                metrics.prunedByStrategy++;
                return true;
            }
        }
        return false;
    }

    /**
     * Exact: W_v(S) ≥ minWeight ⟺ there exists an embedding in which every assigned
     * vertex of G has vertexWeight ≥ minWeight.
     */
    @Override
    public boolean acceptFrequent(Pattern p, int support) {
        return counter.embedsWithMinVertexWeight(p, minWeight);
    }

    public double minWeight() {
        return minWeight;
    }

    public int weightyTripleCount() {
        return weightyTriples.size();
    }
}
