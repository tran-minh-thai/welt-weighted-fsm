package welt.core;

/**
 * Measurement counters shared by all algorithms, exported as CSV per the spec:
 * algorithm, dataset, minSup, minWeight, k, candidateCount, isoCallCount,
 * frequentCount, timeMs, peakMemMB.
 *
 * <p>{@code isoCallCount} (the number of subgraph-isomorphism embedding searches) is
 * the core metric showing that WeLT's filter shaves off exactly the NP-Hard part: all
 * strategies SHARE {@link MniSupportCounter} so the count is fair.
 */
public final class Metrics {

    public long candidateCount = 0;   // candidate patterns considered (after pre-prune)
    public long isoCallCount = 0;     // TOTAL embedding searches (top-level) = mni + weight
    public long mniIsoCalls = 0;      // EXPENSIVE iso-calls: MNI counting (findEmbedding in support)
    public long weightIsoCalls = 0;   // CHEAP iso-calls: weight checks (embedsWithMinWeight/VertexWeight)
    public long backtrackNodes = 0;   // backtracking tree nodes (auxiliary detail)
    public long budgetExceeded = 0;   // embedding searches ABORTED for exceeding the budget (direction A, approximate)
    public long frequentCount = 0;    // FWS patterns found
    public long prunedByStrategy = 0; // candidates pruned by the strategy before MNI counting
    public long prunedByP1 = 0;       // pruned by the structural filter (infrequent k-edge subpattern)
    public long prunedByP2 = 0;       // pruned by the weight filter (UB_k < minWeight)
    public long prunedByApriori = 0;  // pruned by the full anti-monotone closure (infrequent (k-1)-edge subpattern)
    public long tableBuildIsoCalls = 0;    // total iso-calls while building the lookup table (MNI + weight)
    public long tableBuildMniIsoCalls = 0; // just the MNI iso-calls while building the table (to separate the mining part)
    public long timeMs = 0;
    public double peakMemMB = 0;

    public static String csvHeader() {
        return "algorithm,dataset,minSup,minWeight,k,candidateCount,isoCallCount,mniIsoCalls,"
                + "tableBuildIsoCalls,mineMniIsoCalls,weightIsoCalls,frequentCount,timeMs,peakMemMB";
    }

    public String csvRow(String algorithm, String dataset, int minSup, double minWeight, int k) {
        // actual MNI iso-calls of the MINING part (excluding table building) = fair vs GraMi/WEGM
        long mineMni = mniIsoCalls - tableBuildMniIsoCalls;
        return String.format("%s,%s,%d,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.1f",
                algorithm, dataset, minSup,
                Double.isNaN(minWeight) ? "NA" : String.valueOf(minWeight),
                k, candidateCount, isoCallCount, mniIsoCalls, tableBuildIsoCalls, mineMni,
                weightIsoCalls, frequentCount, timeMs, peakMemMB);
    }

    /** Sample raw heap usage (after a gc call). For reference only. */
    public void sampleMemory() {
        Runtime rt = Runtime.getRuntime();
        double usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0);
        if (usedMB > peakMemMB) peakMemMB = usedMB;
    }
}
