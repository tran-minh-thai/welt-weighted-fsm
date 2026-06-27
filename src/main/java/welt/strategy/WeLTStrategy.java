package welt.strategy;

import welt.core.LabeledWeightedGraph;
import welt.core.Metrics;
import welt.core.MniSupportCounter;
import welt.core.Pattern;

/**
 * WeLT (proposed) — edge bottleneck weight + double filter.
 *
 * <p>FWS: {@code MNI(S) ≥ minSup} AND {@code W(S) ≥ minWeight}, with
 * {@code W(S)=max} over occurrences of the {@code min} edge weight.
 *
 * <p>Plugs into the shared engine through three hooks:
 * <ul>
 *   <li>{@link #prePrune} — DOUBLE FILTER (structural P1 + weight P2) via
 *       {@link WeightedLookupTable}, run BEFORE the (expensive) MNI count.</li>
 *   <li>{@link #acceptFrequent} — EXACT evaluation of {@code W(S) ≥ minWeight}
 *       by embedding into G_{≥w} (weight branch-and-bound lemma).</li>
 *   <li>{@link #allowExtension} — always extend frequent patterns (per the paper's
 *       algorithm); weak subtrees are pruned by P2 at the child nodes.</li>
 * </ul>
 *
 * All MNI counting and weight checks SHARE the same {@link MniSupportCounter} +
 * {@link Metrics} with the engine ⇒ a fair aggregate {@code isoCallCount}.
 */
public final class WeLTStrategy implements MiningStrategy {

    private final double minWeight;
    private final MniSupportCounter counter;
    private final WeightedLookupTable table;
    private final Metrics metrics;
    private boolean doubleFilter = true; // ablation toggle: when false, skip the P1/P2 pre-filter

    public WeLTStrategy(LabeledWeightedGraph g, double minWeight,
                        MniSupportCounter counter, WeightedLookupTable table, Metrics metrics) {
        this.minWeight = minWeight;
        this.counter = counter;
        this.table = table;
        this.metrics = metrics;
    }

    /** Ablation hook (RQ4): turn the lookup-table double filter on/off. The result set is
     *  unchanged because the exact accept check still runs; only performance differs. */
    public void setDoubleFilter(boolean on) {
        this.doubleFilter = on;
    }

    @Override
    public String name() {
        return "WeLT";
    }

    /** Double filter P1/P2 before the MNI count (skipped when the ablation toggle is off). */
    @Override
    public boolean prePrune(Pattern candidate) {
        if (!doubleFilter) return false;
        return table.prune(candidate, minWeight, metrics);
    }

    // Cache the exact weight-check result for the pattern just evaluated
    // (allowExtension and acceptFrequent are called consecutively for the same p ⇒
    // compute it only ONCE).
    private Pattern lastP;
    private boolean lastWeightOk;

    private boolean weightOk(Pattern p) {
        if (p != lastP) {
            lastWeightOk = counter.embedsWithMinWeight(p, minWeight);
            lastP = p;
        }
        return lastWeightOk;
    }

    /** Accept as FWS ⇔ W(S) ≥ minWeight (exact evaluation via G_{≥w}). */
    @Override
    public boolean acceptFrequent(Pattern p, int support) {
        return weightOk(p);
    }

    /**
     * OPTIMIZATION: only extend patterns with W(S) ≥ minWeight. Since the bottleneck
     * weight is ANTI-MONOTONE (W(descendant) ≤ W(p)), if W(p) &lt; minWeight then EVERY
     * descendant is &lt; minWeight ⇒ no FWS exists ⇒ PRUNE THE WHOLE SUBTREE (rather than
     * relying on P2 at the child nodes, which is loose when UB_k is loose). Safe: every
     * child FWS has a parent with W ≥ minWeight, so nothing is missed.
     */
    @Override
    public boolean allowExtension(Pattern p, int support) {
        return weightOk(p);
    }

    public double minWeight() {
        return minWeight;
    }

    public WeightedLookupTable table() {
        return table;
    }
}
