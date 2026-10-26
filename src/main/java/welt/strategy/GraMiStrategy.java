package welt.strategy;

/**
 * GraMi baseline, an independent reimplementation of the CSP-based MNI evaluation described
 * by Elseidy et al. (PVLDB 2014); no GraMi source code is included. UNWEIGHTED, pruning by
 * MNI support only. All hooks keep
 * the neutral defaults of {@link MiningStrategy} — this is the reference point for
 * measuring the candidate/time reduction of the weighted algorithms.
 */
public final class GraMiStrategy implements MiningStrategy {

    @Override
    public String name() {
        return "GraMi";
    }
}
