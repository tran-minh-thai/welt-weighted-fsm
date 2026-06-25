package welt.core;

import welt.strategy.MiningStrategy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Frequent subgraph mining engine (FSM over a single large graph) SHARED by all
 * algorithms. The differing parts (pruning, weight conditions) live in
 * {@link MiningStrategy}; the shared part consists of:
 * <ol>
 *   <li>finding frequent vertex labels (the MNI of a single vertex = number of vertices
 *       with that label),</li>
 *   <li>generating single-edge patterns from the triples (labelA, edgeLabel, labelB)
 *       ACTUALLY present in G,</li>
 *   <li>extending each frequent pattern by one edge (leaf or chord), deduplicating via
 *       {@link CanonicalCode},</li>
 *   <li>counting support via the shared {@link MniSupportCounter}.</li>
 * </ol>
 *
 * <p>Completeness: MNI is anti-monotone, so extending only frequent patterns suffices;
 * every frequent (k+1)-edge pattern arises from some connected frequent k-edge
 * subpattern (by removing one leaf edge or one chord). The result MATCHES the frequent
 * subgraph set of the original GraMi (verified against the citeseer_unlabeled oracle).
 */
public final class MiningEngine {

    private final LabeledWeightedGraph g;
    private final Metrics metrics;
    private final MniSupportCounter counter;

    // data index (shared)
    private GraphIndex index;

    public MiningEngine(LabeledWeightedGraph g, Metrics metrics) {
        this.g = g;
        this.metrics = metrics;
        this.counter = new MniSupportCounter(g, metrics);
    }

    /**
     * Set the extension-point voter for the internal MNI counting (ablation α,β,γ).
     * Affects performance only (variable order), not the result set.
     */
    public void setVoter(PivotVoter voter) {
        counter.setVoter(voter);
    }

    /** Direction A: set the embedding-search budget for internal MNI counting (see MniSupportCounter). */
    public void setSearchBudget(long maxBacktrackPerSearch) {
        counter.setSearchBudget(maxBacktrackPerSearch);
    }

    /**
     * TIME LIMIT: an ABSOLUTE deadline (nanoTime) applied to MNI counting. Set the same
     * value on the strategy's counter (table building / weight checks) to cover the whole
     * run. On overshoot, mining STOPS early and {@link #isTimedOut()}=true ⇒ the benchmark
     * records T.O.
     */
    private boolean timedOut = false;

    public void setDeadline(long nanos) {
        counter.setDeadline(nanos);
    }

    public boolean isTimedOut() {
        return timedOut;
    }

    /**
     * Enable/disable FULL ANTI-MONOTONE closure pruning (Mechanism #1): before calling
     * MNI (expensive), check whether EVERY connected {@code (k-1)}-edge subpattern is
     * already frequent — relying on the level-order BFS (every smaller pattern is
     * processed first). Soundness-safe (MNI anti-monotonicity); it only reduces the
     * number of MNI calls, not the result set. Enabled by default.
     */
    public void setAprioriPruning(boolean on) {
        this.aprioriPruning = on;
    }

    private boolean aprioriPruning = true;
    private final Set<String> frequentCodes = new HashSet<>(); // memoized canonical codes of FREQUENT patterns

    // Performance profiling: print SLOW candidates to stderr (enabled via env DEBUG_PROFILE_MS=<ms>)
    private static final boolean PROFILE = System.getenv("DEBUG_PROFILE_MS") != null;
    private static final long PROFILE_MS = PROFILE ? Long.parseLong(System.getenv("DEBUG_PROFILE_MS")) : 0;

    /**
     * Enable/disable parent→child DOMAIN INHERITANCE (decremental). Enabled by default:
     * a child filters its domain from the parent's candidate set instead of scanning all
     * of G. Disable to measure its contribution in isolation (large benefit on large
     * graphs with high |V|). Does not change the result set.
     */
    private boolean domainInheritance = true;

    public void setDomainInheritance(boolean on) {
        this.domainInheritance = on;
    }

    /** Mining result: the list of frequent patterns (≥1 edge) + their support. */
    public static final class FrequentPattern {
        public final Pattern pattern;
        public final int support; // MNI (may be capped at minSup due to early stopping)

        FrequentPattern(Pattern pattern, int support) {
            this.pattern = pattern;
            this.support = support;
        }
    }

    private long mineStart;

    public List<FrequentPattern> mine(int minSup, MiningStrategy strategy) {
        this.index = new GraphIndex(g, minSup);
        frequentCodes.clear();
        mineStart = System.nanoTime();
        timedOut = false;

        List<FrequentPattern> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<FrontierItem> frontier = new ArrayDeque<>();

        // ---- Level 1: single-edge patterns (no parent ⇒ parent domains = null) ----
        for (long key : index.presentTriples) {
            int lo = (int) ((key >> 42) & 0x1FFFFF);
            int hi = (int) ((key >> 21) & 0x1FFFFF);
            int el = (int) (key & 0x1FFFFF);
            if (!index.frequentLabels.contains(lo) || !index.frequentLabels.contains(hi)) continue;
            Pattern p = Pattern.singleEdge(lo, hi, el);
            considerCandidate(p, null, minSup, strategy, result, seen, frontier);
        }

        // ---- Level-wise extension (BFS over the pattern lattice); a child INHERITS the parent's domains ----
        while (!frontier.isEmpty()) {
            if (counter.isTimedOut()) { timedOut = true; break; } // TIME LIMIT
            FrontierItem parent = frontier.poll();
            for (Pattern child : extend(parent.pattern)) {
                considerCandidate(child, parent.domains, minSup, strategy, result, seen, frontier);
                if (counter.isTimedOut()) { timedOut = true; break; }
            }
        }

        if (counter.isTimedOut()) timedOut = true;
        metrics.frequentCount = result.size();
        metrics.sampleMemory();
        return result;
    }

    /** A pattern awaiting extension, with its reduced domains (for children to inherit, avoiding rebuild + recount). */
    private static final class FrontierItem {
        final Pattern pattern;
        final int[][] domains;
        FrontierItem(Pattern pattern, int[][] domains) { this.pattern = pattern; this.domains = domains; }
    }

    /** Consider a candidate: deduplicate, pre-prune, count MNI (inheriting parent domains), classify. */
    private void considerCandidate(Pattern p, int[][] parentDomains, int minSup, MiningStrategy strategy,
                                   List<FrequentPattern> result, Set<String> seen,
                                   Deque<FrontierItem> frontier) {
        String code = CanonicalCode.of(p);
        if (!seen.add(code)) return; // isomorphic pattern already considered

        if (strategy.prePrune(p)) {
            return;
        }
        // Mechanism #1 — FULL ANTI-MONOTONE CLOSURE: every connected (k-1)-edge subpattern
        // must already be frequent (memoized in BFS order). If one is missing ⇒ MNI(p) <
        // minSup for certain ⇒ prune BEFORE calling MNI (expensive). Anti-monotone ⇒ no FWS missed.
        if (aprioriPruning && p.edgeCount() >= 2 && !allSubpatternsFrequent(p)) {
            metrics.prunedByApriori++;
            return;
        }
        metrics.candidateCount++;
        // Inherit parent domains (decremental): the child initializes its domains from the
        // parent's reduced domains; only the new vertex (leaf) builds a full domain; AC
        // propagates the new edge constraint. MNI is preserved.
        long _t0 = PROFILE ? System.nanoTime() : 0;
        long _iso0 = PROFILE ? metrics.mniIsoCalls : 0;
        long _bt0 = PROFILE ? metrics.backtrackNodes : 0;
        MniSupportCounter.SupportResult r =
                counter.supportWithDomains(p, minSup, domainInheritance ? parentDomains : null);
        if (PROFILE) {
            long ms = (System.nanoTime() - _t0) / 1_000_000;
            if (ms >= PROFILE_MS) {
                System.err.printf("[slow cand] n=%d e=%d %dms iso=%d bt=%d -> %s%n",
                        p.nodeCount(), p.edgeCount(), ms, metrics.mniIsoCalls - _iso0,
                        metrics.backtrackNodes - _bt0, r.mni == MniSupportCounter.INFREQUENT ? "INFREQ" : "freq=" + r.mni);
            }
            if (metrics.candidateCount % 200 == 0) {
                System.err.printf("[progress] cand=%d freq=%d iso=%d bt=%d %dms%n",
                        metrics.candidateCount, result.size(), metrics.mniIsoCalls,
                        metrics.backtrackNodes, (System.nanoTime() - mineStart) / 1_000_000);
            }
        }
        if (r.mni == MniSupportCounter.INFREQUENT) return;

        frequentCodes.add(code); // MNI-frequent ⇒ memoize for pruning larger patterns
        // MNI-frequent ⇒ extendable (if the strategy allows); pass domains to children
        if (strategy.allowExtension(p, r.mni)) frontier.add(new FrontierItem(p, r.domains));
        // accept as FWS if the strategy accepts it (GraMi: always accepts)
        if (strategy.acceptFrequent(p, r.mni)) result.add(new FrequentPattern(p, r.mni));
    }

    /** Are all connected (k-1)-edge subpatterns of p present in the frequent memo? */
    private boolean allSubpatternsFrequent(Pattern p) {
        for (Pattern sub : p.connectedEdgeDeletedSubpatterns()) {
            if (!frequentCodes.contains(CanonicalCode.of(sub))) return false;
        }
        return true;
    }

    /** Generate all (k+1)-edge patterns from a k-edge pattern: add a leaf or add a chord. */
    private List<Pattern> extend(Pattern p) {
        List<Pattern> children = new ArrayList<>();
        int n = p.nodeCount();

        // (a) add a LEAF: a new vertex labeled lb joined to the existing vertex u by an edge el
        for (int u = 0; u < n; u++) {
            int lu = p.nodeLabel(u);
            for (int lb : index.frequentLabels) {
                for (int el : index.edgeLabels) {
                    if (!index.triplePresent(lu, lb, el)) continue;
                    children.add(p.withLeaf(u, lb, el));
                }
            }
        }
        // (b) add a CHORD: an edge el between two existing, not-yet-adjacent vertices u,w
        for (int u = 0; u < n; u++) {
            for (int w = u + 1; w < n; w++) {
                if (p.hasEdge(u, w)) continue;
                int lu = p.nodeLabel(u), lw = p.nodeLabel(w);
                for (int el : index.edgeLabels) {
                    if (!index.triplePresent(lu, lw, el)) continue;
                    children.add(p.withChord(u, w, el));
                }
            }
        }
        return children;
    }

    public Map<Integer, Integer> frequentLabelCounts() {
        Map<Integer, Integer> m = new HashMap<>();
        for (int l : index.frequentLabels) m.put(l, index.labelCount[l]);
        return m;
    }
}
