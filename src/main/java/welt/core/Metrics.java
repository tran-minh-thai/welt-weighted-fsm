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
    public long backtrackNodes = 0;   // backtracking tree nodes, total = backtrackMni + backtrackWeight
    public long backtrackMni = 0;     // backtracking nodes inside MNI embedding searches
    public long backtrackWeight = 0;  // backtracking nodes inside weight checks (edge or vertex)
    public long voteNanos = 0;        // time spent in PivotVoter (variable order + pivot choice)

    // Phase timers of the mining phase, recorded only when phaseTiming is true (ablation runs);
    // the main benchmark leaves them off so they add no clock reads to its measurements.
    public boolean phaseTiming = false;
    public long canonNanos = 0;       // canonical code of each generated pattern (deduplication)
    public long filterNanos = 0;      // strategy pre-filter (P1/P2 or edge type) and the P3 closure check
    public long mniNanos = 0;         // MNI counting of candidates
    public long weightNanos = 0;      // weight condition (allowExtension + acceptFrequent)
    public long budgetExceeded = 0;   // MNI embedding searches ABORTED by the budget (approximate mode only; 0 in exact mode)
    public long frequentCount = 0;    // FWS patterns found (set when mining completes)
    public long resultCount = 0;      // FWS patterns accepted so far (incremental: valid after TO/OOM too)
    public long prunedByStrategy = 0; // candidates pruned by the strategy before MNI counting (sum of the next three)
    public long prunedByP1 = 0;       // WeLT table: structural filter (a 2-edge subpattern not in F_k)
    public long prunedByP2 = 0;       // WeLT table: weight filter (UB_k < minWeight), including the next one
    public long prunedByP2Single = 0; // WeLT table: P2 on 1-edge patterns (heaviest edge of the type < minWeight)
    public long prunedByEdgeType = 0; // WEGM/OWGraMi: an edge type that cannot reach the weight threshold
    public long prunedByApriori = 0;  // P3: full anti-monotone closure (a (k-1)-edge subpattern not frequent)

    // Search-space counters of the mining phase, incremented as mining proceeds (so they are
    // the values reached at the stop for a TO or OOM run). For a completed run:
    //   generated = prunedByStrategy + prunedByApriori + candidateCount
    //   candidateCount >= mniFrequent >= resultCount,   mniFrequent >= extended
    public long generated = 0;        // N: distinct patterns generated (after canonical-code deduplication, before any filter)
    public long mniFrequent = 0;      // M': patterns with MNI >= minSup
    public long extended = 0;         // patterns put on the frontier to be extended
    public long frontierPeak = 0;     // largest frontier size
    public long frequentMemoSize = 0; // size of the memo of frequent canonical codes used by P3
    // Retained data, counted from its definition (deterministic; independent of the JVM and the GC):
    public long visitedCodeChars = 0;      // characters of the canonical codes kept for deduplication (one per generated pattern)
    public long memoCodeChars = 0;         // characters of the canonical codes in the frequent memo used by P3
    public long frontierDomainInts = 0;    // vertex ids held now in the domains of frontier patterns and of the pattern being extended
    public long frontierDomainIntsPeak = 0; // largest value of frontierDomainInts
    public long tableKeptPatterns = 0;     // 1- and 2-edge support results kept by the lookup table (keepSupports)
    public long tableDomainInts = 0;       // vertex ids in the domains of those kept results
    public long tableBuildIsoCalls = 0;    // total iso-calls while building the lookup table (MNI + weight)
    public long tableBuildMniIsoCalls = 0; // just the MNI iso-calls while building the table (to separate the mining part)
    public long tableBuildWeightIsoCalls = 0; // weight iso-calls while building the table (MaxW binary searches)
    public long tableBuildCandidates = 0;  // 1- and 2-edge patterns whose MNI was evaluated while building the table
    public long supportReused = 0;    // candidates whose support result came from the table build instead of a recount
    public long weightReused = 0;     // weight checks answered by the threshold table instead of an embedding search
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
