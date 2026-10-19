package welt.strategy;

import welt.core.MniSupportCounter;
import welt.core.Pattern;

/**
 * GraMiFilter — the two-step solution of WeLT's problem with a published algorithm: mine the
 * frequent subgraphs exactly as GraMi does (no weight-based pruning, every MNI-frequent pattern
 * is extended), then keep the patterns that have an occurrence whose edges all weigh at least
 * minWeight (W(S) ≥ minWeight, checked exactly by an embedding into G_{≥minWeight}).
 *
 * <p>This is the design of ReSuM (Preti et al., EDBT 2018; Distrib. Parallel Databases 2019),
 * which mines with GraMi and then validates the appearances of each frequent pattern against the
 * weight constraint, applied here to WeLT's acceptance criterion. Each frequent pattern is checked
 * once, when it is found; checking all of them after mining costs the same. The result set equals
 * WeLT's.
 */
public final class GraMiFilterStrategy implements MiningStrategy {

    private final double minWeight;
    private final MniSupportCounter counter;

    public GraMiFilterStrategy(double minWeight, MniSupportCounter counter) {
        this.minWeight = minWeight;
        this.counter = counter;
    }

    @Override
    public String name() {
        return "GraMiFilter";
    }

    /** Exact weight condition: W(S) ≥ minWeight ⟺ S embeds into G_{≥minWeight}. */
    @Override
    public boolean acceptFrequent(Pattern p, int support) {
        return counter.embedsWithMinWeight(p, minWeight);
    }

    public double minWeight() {
        return minWeight;
    }
}
