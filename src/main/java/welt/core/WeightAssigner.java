package welt.core;

/**
 * Edge weight assignment function. Separates two distinct notions:
 *   - the "edge label used for structural matching" (see {@link EdgeLabelAssigner})
 *   - the "weight" (the quantity used by the pruning rules of weighted algorithms).
 *
 * For CiteSeer, the raw value on an edge line (the 3rd token) is a similarity score
 * and is used directly as the weight, but this interface allows reconfiguration for
 * other datasets (e.g. weight = 1/sim, or a log transform, ...).
 */
@FunctionalInterface
public interface WeightAssigner {

    /**
     * @param src      source vertex id (as in the file)
     * @param dst      destination vertex id (as in the file)
     * @param rawValue raw value read from the 3rd token of the edge line
     * @return the positive weight of the edge
     */
    double weightOf(int src, int dst, double rawValue);

    /** Default for CiteSeer: weight equals the raw edge-label value (similarity). */
    WeightAssigner IDENTITY = (src, dst, rawValue) -> rawValue;
}
