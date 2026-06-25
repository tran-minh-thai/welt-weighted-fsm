package welt.strategy;

import welt.core.LabeledWeightedGraph;
import welt.core.Metrics;
import welt.core.MniSupportCounter;
import welt.core.Pattern;

import java.util.HashMap;
import java.util.Map;

/**
 * WEGM (Weighted Edge Graph Mining) — EDGE-weighted baseline, a reimplementation
 * from the paper's description. Uses the same definition of W(S) (edge bottleneck)
 * as WeLT but WITHOUT the structural lookup table F_k: it prunes only by the
 * single-edge weight upper bound (the simple P2).
 *
 * <p><b>Note:</b> This is a REIMPLEMENTATION from the paper's description (no
 * original source code); implementation details may differ from the original —
 * state this explicitly in the report.
 *
 * <p>Compared with WeLT:
 * <ul>
 *   <li>Same FWS result set (W(S) anti-monotone + same threshold → identical results).</li>
 *   <li>No P1 (structural filter) → more candidates pass the pre-prune.</li>
 *   <li>Simpler P2: checks the maximum weight per single edge type, without
 *       computing UB_k over 2-edge patterns.</li>
 * </ul>
 *
 * <p>Main comparison metrics: {@code candidateCount} and {@code isoCallCount} —
 * showing that WEGM needs more MNI calls than WeLT at high thresholds.
 */
public final class WEGMStrategy implements MiningStrategy {

    private final double minWeight;
    private final MniSupportCounter counter;
    private final Metrics metrics;
    /**
     * Single-edge weight upper bound: triple_key → max ω over all edges of that type in G.
     * Key: (min_label << 42 | max_label << 21 | edge_label) — same encoding as GraphIndex.
     */
    private final Map<Long, Double> maxWeightByTriple;

    public WEGMStrategy(LabeledWeightedGraph g, double minWeight,
                        MniSupportCounter counter, Metrics metrics) {
        this.minWeight = minWeight;
        this.counter = counter;
        this.metrics = metrics;
        this.maxWeightByTriple = buildMaxWeightByTriple(g);
    }

    private static Map<Long, Double> buildMaxWeightByTriple(LabeledWeightedGraph g) {
        Map<Long, Double> m = new HashMap<>();
        for (int u = 0; u < g.numVertices(); u++) {
            int lu = g.vertexLabel(u);
            for (LabeledWeightedGraph.Adjacency adj : g.neighbors(u)) {
                if (adj.to <= u) continue; // visit each edge only once (a < b)
                int lv = g.vertexLabel(adj.to);
                int la = Math.min(lu, lv), lb = Math.max(lu, lv);
                long key = ((long) la << 42) | ((long) lb << 21) | adj.edgeLabel;
                m.merge(key, adj.weight, Math::max);
            }
        }
        return m;
    }

    @Override
    public String name() {
        return "WEGM";
    }

    /**
     * Simple P2 (single-edge): if the pattern contains an edge for which no edge in G
     * of the same type reaches weight ≥ minWeight → W(S) < minWeight → prune.
     *
     * <p>Correctness: W(S) ≤ ω of any edge in G in the best occurrence; if the global
     * max ω of that edge type is &lt; minWeight, no occurrence can suffice.
     */
    @Override
    public boolean prePrune(Pattern s) {
        for (int[] e : s.edges()) {
            int la = Math.min(s.nodeLabel(e[0]), s.nodeLabel(e[1]));
            int lb = Math.max(s.nodeLabel(e[0]), s.nodeLabel(e[1]));
            long key = ((long) la << 42) | ((long) lb << 21) | e[2];
            Double maxW = maxWeightByTriple.get(key);
            if (maxW == null || maxW < minWeight) {
                metrics.prunedByP2++;
                metrics.prunedByStrategy++;
                return true;
            }
        }
        return false;
    }

    /** Exact: W(S) ≥ minWeight ⟺ S embeds into G_{≥minWeight}. */
    @Override
    public boolean acceptFrequent(Pattern p, int support) {
        return counter.embedsWithMinWeight(p, minWeight);
    }

    public double minWeight() {
        return minWeight;
    }
}
