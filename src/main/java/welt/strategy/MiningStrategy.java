package welt.strategy;

import welt.core.Pattern;

/**
 * Plug-in point of an algorithm into the shared mining engine. The engine handles
 * the common parts (candidate generation, MNI counting, deduplication); each
 * strategy decides the PRUNING RULES and (for weighted algorithms) the additional
 * acceptance condition.
 *
 * <p>The unweighted GraMi baseline prunes by MNI only, so both hooks are "neutral".
 * The weighted strategies (OWGraMi/WEGM/WeLT) override them to (i) prune candidates
 * before MNI counting — a cheap filter, and (ii) filter results by the weight
 * condition.
 */
public interface MiningStrategy {

    String name();

    /**
     * Pre-filter BEFORE the (expensive) MNI count. Return {@code true} to DISCARD
     * this candidate. Any pruning here must be a NECESSARY condition of FWS (no
     * misses). GraMi: always {@code false}.
     */
    default boolean prePrune(Pattern candidate) {
        return false;
    }

    /**
     * Once MNI ≥ minSup is known, decide whether to accept the pattern as a result
     * (FWS). Used for the weight condition (W(S) ≥ minWeight). GraMi: always
     * {@code true}.
     */
    default boolean acceptFrequent(Pattern p, int support) {
        return true;
    }

    /**
     * Whether to allow EXTENDING from this pattern (even when it is not accepted as
     * a result). Since MNI support is anti-monotone, the engine only extends
     * frequent patterns; a strategy may narrow this further. GraMi: always
     * {@code true}.
     */
    default boolean allowExtension(Pattern p, int support) {
        return true;
    }
}
