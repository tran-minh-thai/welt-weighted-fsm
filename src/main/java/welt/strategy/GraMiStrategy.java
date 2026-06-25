package welt.strategy;

/**
 * Original GraMi baseline (adapted from the open-source code of Elseidy &amp;
 * Abdelhamid, PVLDB 2014): UNWEIGHTED, pruning by MNI support only. All hooks keep
 * the neutral defaults of {@link MiningStrategy} — this is the reference point for
 * measuring the candidate/time reduction of the weighted algorithms.
 */
public final class GraMiStrategy implements MiningStrategy {

    @Override
    public String name() {
        return "GraMi";
    }
}
