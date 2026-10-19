package welt.strategy;

import welt.core.LabeledWeightedGraph;
import welt.core.Metrics;
import welt.core.MniSupportCounter;
import welt.core.Pattern;

import java.util.HashMap;
import java.util.Map;

/**
 * EdgeTypeBound — a baseline for the SAME problem as WeLT (edge-bottleneck weight
 * W(S) = max over occurrences of the minimum edge weight; S is a result iff MNI(S) ≥ minSup
 * and W(S) ≥ minWeight), WITHOUT WeLT's lookup table and voting:
 * <ul>
 *   <li>pre-filter: a pattern containing an edge type (labelA, edgeLabel, labelB) whose
 *       heaviest edge in G is below minWeight cannot reach the threshold and is pruned before
 *       MNI counting (the per-edge-type bound);</li>
 *   <li>acceptance: exact check that S embeds into G restricted to edges of weight ≥ minWeight;</li>
 *   <li>extension: by default only patterns that pass the weight check are extended (W is
 *       anti-monotone, so no result is lost). {@code gateExtension=false} extends every
 *       MNI-frequent pattern instead (the behaviour of the first submission).</li>
 * </ul>
 * Its result set must equal WeLT's (same problem), which the oracle tests verify.
 *
 * <p><b>This is not WEGM.</b> The first submission called this strategy "WEGM". The WEGM
 * paper (Nguyen et al., J. Inf. Telecommun. 9(4):543-558, 2025) treats an edge weight as part
 * of the pattern: isomorphism requires equal weights (Definition 3(b)), support counts only
 * occurrences with those weights, and W_E(S) is computed from the pattern's own edges
 * (Definition 6). See {@link WEGMStrategy} for that algorithm. The per-edge-type bound used
 * here plays the role of WEGM's early pruning of the frequent-edge list, transferred to the
 * bottleneck model.
 */
public final class EdgeTypeBoundStrategy implements MiningStrategy {

    private final double minWeight;
    private final MniSupportCounter counter;
    private final Metrics metrics;
    private final boolean gateExtension;
    /**
     * Single-edge weight upper bound: triple_key → max ω over all edges of that type in G.
     * Key: (min_label << 42 | max_label << 21 | edge_label) — same encoding as GraphIndex.
     */
    private final Map<Long, Double> maxWeightByTriple;

    public EdgeTypeBoundStrategy(LabeledWeightedGraph g, double minWeight,
                                 MniSupportCounter counter, Metrics metrics) {
        this(g, minWeight, counter, metrics, true);
    }

    public EdgeTypeBoundStrategy(LabeledWeightedGraph g, double minWeight,
                                 MniSupportCounter counter, Metrics metrics, boolean gateExtension) {
        this.minWeight = minWeight;
        this.counter = counter;
        this.metrics = metrics;
        this.gateExtension = gateExtension;
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
        return gateExtension ? "EdgeTypeBound" : "EdgeTypeBound-ungated";
    }

    /**
     * Per-edge-type bound: if the pattern contains an edge for which no edge in G of the same
     * type reaches weight ≥ minWeight, then W(S) &lt; minWeight → prune.
     *
     * <p>Correctness: W(S) ≤ ω of any edge in G in the best occurrence; if the global max ω of
     * that edge type is &lt; minWeight, no occurrence can suffice.
     */
    @Override
    public boolean prePrune(Pattern s) {
        for (int[] e : s.edges()) {
            int la = Math.min(s.nodeLabel(e[0]), s.nodeLabel(e[1]));
            int lb = Math.max(s.nodeLabel(e[0]), s.nodeLabel(e[1]));
            long key = ((long) la << 42) | ((long) lb << 21) | e[2];
            Double maxW = maxWeightByTriple.get(key);
            if (maxW == null || maxW < minWeight) {
                metrics.prunedByEdgeType++;
                metrics.prunedByStrategy++;
                return true;
            }
        }
        return false;
    }

    // allowExtension and acceptFrequent are called consecutively for the same pattern:
    // evaluate the weight check once.
    private Pattern lastP;
    private boolean lastWeightOk;

    private boolean weightOk(Pattern p) {
        if (p != lastP) {
            lastWeightOk = counter.embedsWithMinWeight(p, minWeight);
            lastP = p;
        }
        return lastWeightOk;
    }

    /** Exact: W(S) ≥ minWeight ⟺ S embeds into G_{≥minWeight}. */
    @Override
    public boolean acceptFrequent(Pattern p, int support) {
        return weightOk(p);
    }

    /** Extend only patterns with W(S) ≥ minWeight (anti-monotone), unless the gate is off. */
    @Override
    public boolean allowExtension(Pattern p, int support) {
        return !gateExtension || weightOk(p);
    }

    public double minWeight() {
        return minWeight;
    }
}
