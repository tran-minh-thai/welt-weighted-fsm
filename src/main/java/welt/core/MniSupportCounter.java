package welt.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MNI (Minimum Image-based support) counter — a FAITHFUL port of GraMi's CSP
 * algorithm (Elseidy &amp; Abdelhamid, PVLDB 2014, classes {@code CSP.ConstraintGraph}
 * + {@code CSP.DFSSearch}). We do NOT write a new isomorphism solver: every strategy
 * (GraMi/OWGraMi/WEGM/WeLT) SHARES this class; WeLT only REDUCES the number of calls to
 * it rather than replacing it — which keeps the comparison fair.
 *
 * <p><b>MNI definition.</b> Given a pattern P with vertices v0..v_{n-1} and ALL the
 * subgraph-isomorphism embeddings of P into G, the image of vi is the set of G vertices
 * that vi maps to across all embeddings. MNI(P) = min_i |image(vi)|. P is frequent ⇔
 * MNI(P) ≥ minSup.
 *
 * <p><b>Computation (CSP, lazy + push-down as in GraMi).</b> Each pattern vertex is a
 * CSP variable whose domain = the valid G vertices (matching label + degree/neighbor
 * constraints). A G vertex {@code u} is a valid value of variable i ⇔ THERE EXISTS a
 * full embedding mapping vi=u. When an embedding is found, ALL vertices in it are valid
 * values for their respective variables (push-down) ⇒ sharply reduces the number of
 * embedding searches. Early stopping: once a variable reaches minSup valid values it is
 * "satisfied"; if a variable cannot reach minSup (domain exhausted with valid count
 * &lt; minSup) ⇒ MNI &lt; minSup ⇒ NOT frequent.
 *
 * <p>This version is EXACT (it runs the embedding search to completion when proving a
 * value INVALID) — correctness over speed; it does not use AGRAMI's approximate
 * time-out. Each {@link #findEmbedding} call (top-level) counts as one {@code isoCallCount}.
 */
public final class MniSupportCounter {

    private final LabeledWeightedGraph g;
    private final Metrics metrics;

    /**
     * Voter for choosing the matching start vertex (the paper's pivot section). Defaults
     * to {@link PivotVoter#DEGREE_ONLY} = highest degree (reproducing the old heuristic);
     * switch to {@link PivotVoter#WELT_DEFAULT} to enable WeLT's multi-criteria voting.
     * Affects performance ONLY, not the result set.
     */
    private PivotVoter voter = PivotVoter.DEGREE_ONLY;

    // Feature caches built once from G (for PivotVoter)
    private Map<Integer, Integer> labelCountCache;        // vertex label -> count of vertices with that label (domain-size proxy)
    private Map<Long, Double> tripleMaxWCache;            // edge type -> max ω (for the weight signal)

    /** Result flag: proven NOT frequent (MNI &lt; minSup). */
    public static final int INFREQUENT = -1;

    public MniSupportCounter(LabeledWeightedGraph g, Metrics metrics) {
        this.g = g;
        this.metrics = metrics;
    }

    /** Set the extension-point voter (ablation α,β,γ). */
    public void setVoter(PivotVoter voter) {
        this.voter = voter;
    }

    public PivotVoter voter() {
        return voter;
    }

    /**
     * MECHANISM #2 flag: order the MATCHING by weight in the {@link #embedsWithMinWeight}
     * check — match edges with the LOWEST weight ceiling first (most likely to violate
     * τ_w) so the bottleneck lower bound hits the cutoff threshold SOONER (the bnb lemma).
     * Affects performance only (reduces backtrackNodes), not the result. Disabled by
     * default to preserve the old behavior.
     */
    private boolean weightAwareOrdering = false;

    public void setWeightAwareOrdering(boolean on) {
        this.weightAwareOrdering = on;
    }

    /**
     * Enable/disable arc-consistency (AC-3) domain filtering before the embedding search.
     * Enabled by default. Disable to measure this optimization's contribution in isolation
     * (affects performance only, not the MNI).
     */
    private boolean arcConsistencyOn = true;

    public void setArcConsistency(boolean on) {
        this.arcConsistencyOn = on;
    }

    /**
     * Domain mask used during MNI backtracking: {@code activeDomMask[v]} has bit {@code i}
     * set ⇔ G vertex {@code v} belongs to the domain of pattern variable {@code i}.
     * {@code null} when unrestricted (e.g. the weight checks). {@code domMaskScratch} is
     * a reusable buffer.
     */
    private long[] activeDomMask;
    private long[] domMaskScratch;

    /**
     * HUB-GRAPH OPTIMIZATION: the (reduced) domain of each pattern variable, so that during
     * backtracking we iterate the SMALLER of (the anchor's adjacency) and (the variable's
     * domain) — avoiding scans over the huge adjacency of a hub vertex (scale-free
     * networks). {@code null} outside MNI counting.
     */
    private List<int[]> activeDomains;

    /**
     * MEMORY OPTIMIZATION: track "used" G vertices (the injectivity constraint) with a
     * TIMESTAMP instead of a {@code boolean[|V|]} allocated per embedding search.
     * {@code usedStamp[v]==gen} ⇔ v is currently used in the present embedding search.
     * Each top-level embedding search calls {@link #newSearch()} (bumping gen) ⇒ O(1)
     * reset, NO reallocation, NO clearing needed.
     */
    private int[] usedStamp;
    private int usedGen = 0;

    /**
     * DIRECTION A — EMBEDDING-SEARCH BUDGET (GraMi/AGRAMI style): cap the number of
     * backtrack nodes for EACH top-level embedding search. {@link Long#MAX_VALUE} = NO
     * limit (EXACT, the default — kept for the oracle tests + small graphs). When set
     * finite, an embedding search that exceeds the budget is ABORTED (treated as not
     * found) ⇒ MNI becomes a LOWER BOUND (no false positives; may miss some — approximate,
     * in the spirit of the original GraMi's time cutoff). All algorithms share the
     * engine + budget ⇒ the COMPARISON stays fair (same approximate result set).
     */
    private long searchBudget = Long.MAX_VALUE;
    private long searchNodes = 0;
    private boolean searchAborted = false;

    public void setSearchBudget(long maxBacktrackPerSearch) {
        this.searchBudget = maxBacktrackPerSearch <= 0 ? Long.MAX_VALUE : maxBacktrackPerSearch;
    }

    /**
     * TIME LIMIT: a deadline (absolute nanoTime). On overshoot, MNI counting stops early
     * and sets the {@link #isTimedOut()} flag (the result is UNRELIABLE — the engine will
     * record T.O.).
     */
    private long deadlineNanos = Long.MAX_VALUE;
    private boolean timedOut = false;
    private int deadlineTick = 0;

    public void setDeadline(long nanos) {
        this.deadlineNanos = nanos;
        this.timedOut = false;
    }

    public boolean isTimedOut() {
        return timedOut;
    }

    /** Deadline check (sparse — every 256 calls) to avoid overly frequent nanoTime cost. */
    private boolean deadlineExceeded() {
        if (deadlineNanos == Long.MAX_VALUE) return false;
        if ((++deadlineTick & 0xFF) != 0) return timedOut;
        if (System.nanoTime() > deadlineNanos) timedOut = true;
        return timedOut;
    }

    public long searchBudget() {
        return searchBudget;
    }

    private void newSearch() {
        if (usedStamp == null || usedStamp.length < g.numVertices()) {
            usedStamp = new int[g.numVertices()];
            usedGen = 0;
        }
        usedGen++;
        if (usedGen == Integer.MAX_VALUE) { // overflow (extremely rare): reset the array
            Arrays.fill(usedStamp, 0);
            usedGen = 1;
        }
        searchNodes = 0;
        searchAborted = false;
    }

    /** MNI counting result with the reduced domains (to inherit for subpatterns). */
    public static final class SupportResult {
        public final int mni;          // MNI or {@link #INFREQUENT}
        public final int[][] domains;  // reduced domain per vertex; null if INFREQUENT
        SupportResult(int mni, int[][] domains) { this.mni = mni; this.domains = domains; }
    }

    /**
     * @return the actual MNI if ≥ minSup (may be capped at minSup due to early stopping),
     *         or {@link #INFREQUENT} if MNI &lt; minSup has been proven.
     */
    public int support(Pattern p, int minSup) {
        return supportWithDomains(p, minSup, null).mni;
    }

    /**
     * Like {@link #support} but (a) may INHERIT domains from the PARENT pattern
     * ({@code parentDomains[i]} for vertex i, with i &lt; parentDomains.length — an
     * identity mapping since withLeaf/withChord preserve the old vertex indices), and
     * (b) returns the reduced domains for the subpattern to inherit further.
     *
     * <p><b>Correctness of domain inheritance (decremental).</b> Since {@code S ⊇ P} (one
     * added edge), every embedding of S restricts to an embedding of P ⇒ the valid image
     * of S ⊆ the valid image of P ⊆ the reduced domain of P. Hence initializing S's
     * domain from P's reduced domain (for inherited vertices) yields a set that CONTAINS
     * every valid image of S ⇒ nothing is missed ⇒ the computed MNI is EXACT. The NEW
     * vertex (leaf) still builds a full domain. The new edge constraint is propagated by
     * AC. Benefit: skip scanning all of G and start from the parent's small domain
     * (compounding down the pattern lattice).
     */
    public SupportResult supportWithDomains(Pattern p, int minSup, int[][] parentDomains) {
        int n = p.nodeCount();

        // ---- Domain initialization: INHERIT from the parent if present, else build fully ----
        List<int[]> domains = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int[] dom = (parentDomains != null && i < parentDomains.length)
                    ? filterDomain(p, i, parentDomains[i])  // inherit parent + re-filter for child (cheap, tight)
                    : buildDomain(p, i);                    // new vertex (leaf) or no parent
            if (dom.length < minSup) {
                return new SupportResult(INFREQUENT, null); // domain < minSup ⇒ image < minSup
            }
            domains.add(dom);
        }

        // ---- OPTIMIZATION: arc consistency (AC-3) filters domains before the embedding search (expensive) ----
        // Remove u from variable i's domain if i is adjacent to j in the pattern but u has
        // NO G neighbor in domain j (same edge label) — u then cannot belong to any
        // embedding ⇒ safe. This also PROPAGATES the NEW EDGE constraint when inheriting
        // parent domains. If a domain shrinks below minSup ⇒ INFREQUENT is proven WITHOUT
        // any embedding search. MNI is preserved.
        if (arcConsistencyOn && !arcConsistency(p, domains, minSup, n)) {
            return new SupportResult(INFREQUENT, null);
        }

        @SuppressWarnings("unchecked")
        Set<Integer>[] valid = new Set[n];
        for (int i = 0; i < n; i++) valid[i] = new HashSet<>();

        // ---- Variable order from MULTI-CRITERIA VOTING (REAL domain size + degree + weight
        //      UB). The default DEGREE_ONLY roughly preserves the old behavior; DOMAIN_ONLY
        //      reproduces "smallest domain first". Changes performance only, not the
        //      frequency decision. ----
        double[] domSize = new double[n];
        int[] deg = new int[n];
        double[] wUB = new double[n];
        Map<Long, Double> tw = tripleMaxW();
        for (int i = 0; i < n; i++) {
            domSize[i] = domains.get(i).length;
            deg[i] = p.degree(i);
            double ub = Double.POSITIVE_INFINITY;
            for (int w : p.neighbors(i)) {
                int la = Math.min(p.nodeLabel(i), p.nodeLabel(w));
                int lb = Math.max(p.nodeLabel(i), p.nodeLabel(w));
                long key = ((long) la << 42) | ((long) lb << 21) | p.edgeLabelBetween(i, w);
                ub = Math.min(ub, tw.getOrDefault(key, 0.0));
            }
            wUB[i] = ub;
        }
        Integer[] order = voter.order(domSize, deg, wUB);

        // ---- OPTIMIZATION: restrict backtrack candidates BY DOMAIN (reduced by AC). Bit
        //      mask: bit i of domMask[v] is set ⇔ v belongs to variable i's domain. During
        //      backtracking, only accept a G vertex in the domain of the pattern variable
        //      being matched ⇒ prune deep branches that cannot lead to an embedding early.
        //      Safe: a real embedding always lies within the domains.
        boolean useMask = n <= 64;
        if (useMask) {
            if (domMaskScratch == null || domMaskScratch.length < g.numVertices()) {
                domMaskScratch = new long[g.numVertices()];
            }
            for (int i = 0; i < n; i++) {
                long bit = 1L << i;
                for (int v : domains.get(i)) domMaskScratch[v] |= bit;
            }
            activeDomMask = domMaskScratch;
        }
        activeDomains = domains; // enable domain iteration in backtracking (hub optimization)
        try {
            int mni = Integer.MAX_VALUE;
            int[] assign = new int[n]; // REUSED across all values (instead of allocating per value)
            for (int oi = 0; oi < n; oi++) {
                int i = order[oi];
                int[] dom = domains.get(i);
                int[] matchOrder = bfsOrder(p, i); // depends ONLY on (p,i) ⇒ computed once per variable
                for (int idx = 0; idx < dom.length; idx++) {
                    int u = dom[idx];
                    if (valid[i].contains(u)) {
                        if (valid[i].size() >= minSup) break;
                        continue;
                    }
                    if (deadlineExceeded()) return new SupportResult(INFREQUENT, null); // T.O.
                    Arrays.fill(assign, -1);
                    metrics.isoCallCount++;
                    metrics.mniIsoCalls++; // EXPENSIVE iso-call: MNI counting
                    if (findEmbedding(p, i, u, assign, matchOrder)) {
                        // push-down: every vertex in the embedding is a valid value for its variable
                        for (int j = 0; j < n; j++) valid[j].add(assign[j]);
                    }
                    // u invalid ⇒ searched to completion, skip it
                    if (valid[i].size() >= minSup) break;
                }
                if (valid[i].size() < minSup) {
                    return new SupportResult(INFREQUENT, null); // image of variable i < minSup
                }
                mni = Math.min(mni, valid[i].size());
            }
            // frequent: return the reduced domains for subpatterns to inherit
            int[][] reduced = new int[n][];
            for (int i = 0; i < n; i++) reduced[i] = domains.get(i);
            return new SupportResult(mni, reduced);
        } finally {
            if (useMask) {
                for (int i = 0; i < n; i++) {
                    for (int v : domains.get(i)) domMaskScratch[v] = 0L;
                }
                activeDomMask = null;
            }
            activeDomains = null;
        }
    }

    /**
     * Arc consistency (AC-3) for the binary constraints of the embedding problem: for
     * each pattern edge {@code (i,j)} labeled {@code el}, remove {@code u} from domain i
     * if u has no G neighbor in domain j via an edge labeled el. Iterate to a fixed
     * point. Returns {@code false} as soon as a domain shrinks to {@code < minSup}
     * (INFREQUENT proven). Overwrites the reduced domains into {@code domains}.
     *
     * <p>Correctness: a vertex in any embedding always has an embedding-neighbor satisfying
     * the neighbor's domain ⇒ AC NEVER removes a vertex belonging to a real embedding ⇒
     * the valid image set (and hence MNI) is preserved; AC only drops "dead" values to
     * save failed embedding searches.
     */
    private boolean arcConsistency(Pattern p, List<int[]> domains, int minSup, int n) {
        List<Set<Integer>> dom = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int[] d = domains.get(i);
            Set<Integer> s = new HashSet<>(Math.max(16, d.length * 2));
            for (int v : d) s.add(v);
            dom.add(s);
        }
        java.util.Deque<int[]> queue = new java.util.ArrayDeque<>();
        boolean[][] inQ = new boolean[n][n];
        for (int i = 0; i < n; i++) {
            for (int j : p.neighbors(i)) {
                if (!inQ[i][j]) { queue.add(new int[]{i, j}); inQ[i][j] = true; }
            }
        }
        while (!queue.isEmpty()) {
            int[] arc = queue.poll();
            int i = arc[0], j = arc[1];
            inQ[i][j] = false;
            int el = p.edgeLabelBetween(i, j);
            if (revise(dom.get(i), dom.get(j), el)) {
                if (dom.get(i).size() < minSup) return false; // INFREQUENT, no search needed
                for (int k : p.neighbors(i)) {
                    if (k != j && !inQ[k][i]) { queue.add(new int[]{k, i}); inQ[k][i] = true; }
                }
            }
        }
        // overwrite the reduced domains (sorted ascending for stability / cache locality)
        for (int i = 0; i < n; i++) {
            Set<Integer> s = dom.get(i);
            int[] arr = new int[s.size()];
            int k = 0;
            for (int v : s) arr[k++] = v;
            Arrays.sort(arr);
            domains.set(i, arr);
        }
        return true;
    }

    /** Remove the u in {@code di} that have no G neighbor in {@code dj} via edge label el. */
    private boolean revise(Set<Integer> di, Set<Integer> dj, int el) {
        boolean removed = false;
        java.util.Iterator<Integer> it = di.iterator();
        while (it.hasNext()) {
            int u = it.next();
            if (!hasNeighborInSet(u, dj, el)) {
                it.remove();
                removed = true;
            }
        }
        return removed;
    }

    private boolean hasNeighborInSet(int u, Set<Integer> dj, int el) {
        for (LabeledWeightedGraph.Adjacency a : g.neighbors(u)) {
            if (a.edgeLabel == el && dj.contains(a.to)) return true;
        }
        return false;
    }

    /**
     * Initial domain of variable i: the G vertices satisfying the NECESSARY conditions
     * (no false pruning): same vertex label, degree ≥ pattern degree, and for each
     * neighbor label in the pattern, the corresponding neighbor count in G must be ≥ the
     * requirement (also considering the edge label).
     */
    private int[] buildDomain(Pattern p, int i) {
        List<int[]> req = requirements(p, i);
        int targetLabel = p.nodeLabel(i);
        int patDeg = p.degree(i);
        List<Integer> dom = new ArrayList<>();
        for (int v = 0; v < g.numVertices(); v++) {
            if (qualifies(v, targetLabel, patDeg, req)) dom.add(v);
        }
        int[] arr = new int[dom.size()];
        for (int k = 0; k < arr.length; k++) arr[k] = dom.get(k);
        return arr;
    }

    /**
     * Decremental INHERITANCE: filter the PARENT's candidate set by the child's exact
     * NECESSARY conditions (label + degree + neighbor multiset) — iterating only the
     * (small) parent set, NOT scanning all of G. Yields a domain that is both small (a
     * subset of the parent's) and tight (enough new-edge constraint) ⇒ the fewest
     * embedding searches.
     */
    private int[] filterDomain(Pattern p, int i, int[] candidates) {
        List<int[]> req = requirements(p, i);
        int targetLabel = p.nodeLabel(i);
        int patDeg = p.degree(i);
        int[] tmp = new int[candidates.length];
        int k = 0;
        for (int v : candidates) {
            if (qualifies(v, targetLabel, patDeg, req)) tmp[k++] = v;
        }
        return Arrays.copyOf(tmp, k);
    }

    /** Neighbor requirements of variable i: a list of {neighborVertexLabel, edgeLabel}. */
    private List<int[]> requirements(Pattern p, int i) {
        List<int[]> req = new ArrayList<>();
        for (int w : p.neighbors(i)) req.add(new int[]{p.nodeLabel(w), p.edgeLabelBetween(i, w)});
        return req;
    }

    /** Does G vertex {@code v} satisfy the variable's NECESSARY conditions: label + degree + neighbor multiset. */
    private boolean qualifies(int v, int targetLabel, int patDeg, List<int[]> req) {
        if (g.vertexLabel(v) != targetLabel) return false;
        List<LabeledWeightedGraph.Adjacency> adj = g.neighbors(v);
        if (adj.size() < patDeg) return false;
        return satisfiesNeighborMultiset(adj, req);
    }

    /**
     * Check whether a G vertex has enough neighbors to satisfy the required multiset
     * (vertexLabel, edgeLabel), counting multiplicity (greedy matching — a necessary
     * condition, not overly tight).
     */
    private boolean satisfiesNeighborMultiset(List<LabeledWeightedGraph.Adjacency> adj, List<int[]> req) {
        if (req.isEmpty()) return true;
        boolean[] used = new boolean[adj.size()];
        for (int[] r : req) {
            boolean matched = false;
            for (int k = 0; k < adj.size(); k++) {
                if (used[k]) continue;
                LabeledWeightedGraph.Adjacency a = adj.get(k);
                if (g.vertexLabel(a.to) == r[0] && a.edgeLabel == r[1]) {
                    used[k] = true;
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        return true;
    }

    /**
     * Find ONE full embedding of pattern p into G under the constraint {@code p[fixedVar]=fixedVal}.
     * Backtrack in BFS order from fixedVar so that each new vertex is always adjacent to
     * an already-assigned one (good pruning). Returns true and fills {@code assign}
     * (assign[patNode]=gVertex).
     */
    boolean findEmbedding(Pattern p, int fixedVar, int fixedVal, int[] assign, int[] matchOrder) {
        // the G label of fixedVal must match (guaranteed by the domain, but checked anyway)
        if (g.vertexLabel(fixedVal) != p.nodeLabel(fixedVar)) return false;

        newSearch();                       // reset "used" in O(1) (timestamp)
        assign[fixedVar] = fixedVal;
        usedStamp[fixedVal] = usedGen;
        // structural MNI: no weight constraint ⇒ minW = -∞ (every edge valid)
        boolean ok = backtrack(p, matchOrder, 1, assign, Double.NEGATIVE_INFINITY);
        if (searchAborted) metrics.budgetExceeded++; // embedding search aborted by the budget
        if (!ok) {
            // reset assign to -1 so no state leaks on failure
            Arrays.fill(assign, -1);
        }
        return ok;
    }

    /** BFS matching order starting from {@code start} (ensures progressive connectivity). */
    private int[] bfsOrder(Pattern p, int start) {
        int n = p.nodeCount();
        int[] order = new int[n];
        boolean[] seen = new boolean[n];
        int head = 0, tail = 0;
        order[tail++] = start;
        seen[start] = true;
        while (head < tail) {
            int cur = order[head++];
            for (int w : p.neighbors(cur)) {
                if (!seen[w]) {
                    seen[w] = true;
                    order[tail++] = w;
                }
            }
        }
        return order;
    }

    private boolean backtrack(Pattern p, int[] matchOrder, int pos, int[] assign, double minW) {
        metrics.backtrackNodes++;
        if (++searchNodes > searchBudget) { searchAborted = true; return false; } // budget (direction A)
        int n = p.nodeCount();
        if (pos == n) return true;

        int patNode = matchOrder[pos];
        int targetLabel = p.nodeLabel(patNode);

        // Candidate set: the intersection of the (G) neighbors of the already-assigned
        // pattern vertices adjacent to patNode. OPTIMIZATION: pick the "anchor" to be the
        // assigned neighbor with the SMALLEST G DEGREE ⇒ enumerate the fewest candidates
        // (especially effective for patterns with many edges/chords at low thresholds).
        // Sound because consistent() still checks every remaining edge constraint.
        int anchor = -1, anchorDeg = Integer.MAX_VALUE;
        for (int w : p.neighbors(patNode)) {
            if (assign[w] != -1) {
                int d = g.neighbors(assign[w]).size();
                if (d < anchorDeg) { anchorDeg = d; anchor = w; }
            }
        }
        // Because matchOrder is BFS, patNode always has at least one assigned neighbor.
        List<LabeledWeightedGraph.Adjacency> anchorAdj = g.neighbors(assign[anchor]);
        int[] stamp = usedStamp;
        int gen = usedGen;

        // HUB OPTIMIZATION: if patNode's DOMAIN is smaller than the anchor's adjacency (a
        // hub vertex), ITERATE THE DOMAIN and look up the anchor edge via the O(1) index —
        // avoiding a scan over the huge adjacency list.
        int[] dom = (activeDomains != null && patNode < activeDomains.size())
                ? activeDomains.get(patNode) : null;
        if (dom != null && dom.length < anchorAdj.size()) {
            for (int gv : dom) {                                  // gv already guaranteed label + in domain
                if (stamp[gv] == gen) continue;
                if (!consistent(p, patNode, gv, assign, minW)) continue; // includes the anchor edge + ω≥minW
                assign[patNode] = gv;
                stamp[gv] = gen;
                if (backtrack(p, matchOrder, pos + 1, assign, minW)) return true;
                stamp[gv] = 0;
                assign[patNode] = -1;
            }
            return false;
        }

        long maskBit = (activeDomMask != null && patNode < 64) ? (1L << patNode) : 0L;
        for (LabeledWeightedGraph.Adjacency cand : anchorAdj) {
            int gv = cand.to;
            if (stamp[gv] == gen) continue;                       // already used (injectivity)
            if (g.vertexLabel(gv) != targetLabel) continue;
            // DOMAIN RESTRICTION (AC): gv must belong to patNode's domain (if the mask is enabled)
            if (maskBit != 0L && (activeDomMask[gv] & maskBit) == 0L) continue;
            // WEIGHT BRANCH-AND-BOUND (the bnb lemma): only traverse edges with ω ≥ minW.
            // (the anchor→gv edge; the remaining constraints are checked in consistent with the same minW)
            if (cand.weight < minW) continue;
            // check EVERY edge constraint between patNode and the already-assigned pattern vertices
            if (!consistent(p, patNode, gv, assign, minW)) continue;

            assign[patNode] = gv;
            stamp[gv] = gen;
            if (backtrack(p, matchOrder, pos + 1, assign, minW)) return true;
            stamp[gv] = 0;
            assign[patNode] = -1;
        }
        return false;
    }

    /**
     * Can gv be assigned to patNode: for each assigned pattern vertex w, if the pattern
     * has an edge (patNode,w) then G must have an edge (gv,assign[w]) with the same edge
     * label AND ω ≥ minW; if the pattern has NO edge then there is no constraint (an
     * induced subgraph is not required).
     */
    private boolean consistent(Pattern p, int patNode, int gv, int[] assign, double minW) {
        // iterate ONLY patNode's PATTERN neighbors (where an edge constraint exists), looking
        // up the G edge via the O(1) index — instead of scanning all vertices + a linear
        // adjacency scan.
        for (int w : p.neighbors(patNode)) {
            int gw = assign[w];
            if (gw == -1) continue;
            LabeledWeightedGraph.Adjacency e = g.edge(gv, gw);
            if (e == null || e.edgeLabel != p.edgeLabelBetween(patNode, w) || e.weight < minW) {
                return false;
            }
        }
        return true;
    }

    private boolean graphHasEdge(int a, int b, int edgeLabel, double minW) {
        LabeledWeightedGraph.Adjacency e = g.edge(a, b);
        return e != null && e.edgeLabel == edgeLabel && e.weight >= minW;
    }

    // ===================== Weight checks (WeLT) =====================

    /**
     * W(S) ≥ minEdgeWeight ⟺ S embeds into G_{≥w} (the graph keeping edges with ω ≥ w).
     * Implemented as an existence embedding search with weight branch-and-bound (the bnb
     * lemma): traverse only edges with ω ≥ {@code minEdgeWeight}. We do NOT build a
     * separate filtered graph — edges are filtered directly during backtracking
     * (equivalent to building G_{≥w} but cheaper).
     *
     * <p>Counting: each "root anchor" (assigning the start pattern vertex to a G vertex)
     * is one {@code isoCallCount} — the same unit as each value check in MNI.
     */
    public boolean embedsWithMinWeight(Pattern p, double minEdgeWeight) {
        // ONE subgraph-isomorphism question ("does p embed into G_{≥w}") = ONE iso-call,
        // analogous to each value check in MNI. Iterating over many root vertices inside is
        // a search detail (counted in backtrackNodes), not a multiplier on iso-calls.
        metrics.isoCallCount++;
        metrics.weightIsoCalls++; // CHEAP iso-call: edge-weight check
        int n = p.nodeCount();
        int start = chooseStart(p);
        int targetLabel = p.nodeLabel(start);
        int patDeg = p.degree(start);
        // Mechanism #2: matching order prefers edges with a low weight ceiling (cuts τ_w early)
        int[] order = weightAwareOrdering ? weightAwareOrder(p, start) : bfsOrder(p, start);

        int[] assign = new int[n]; // reused across all root vertices
        for (int v = 0; v < g.numVertices(); v++) {
            if (g.vertexLabel(v) != targetLabel) continue;
            if (g.neighbors(v).size() < patDeg) continue;
            Arrays.fill(assign, -1);
            newSearch();
            assign[start] = v;
            usedStamp[v] = usedGen;
            if (backtrack(p, order, 1, assign, minEdgeWeight)) return true;
        }
        return false;
    }

    /**
     * Prim-style matching order: always extend to an unmatched vertex via the edge with
     * the SMALLEST weight CEILING (the global max ω per edge type) — the edge most likely
     * to violate τ_w is matched first so the weight branch-and-bound cuts early. Still
     * progressively connected (an anchor always exists).
     */
    private int[] weightAwareOrder(Pattern p, int start) {
        int n = p.nodeCount();
        Map<Long, Double> tw = tripleMaxW();
        int[] order = new int[n];
        boolean[] inOrder = new boolean[n];
        order[0] = start;
        inOrder[start] = true;
        for (int placed = 1; placed < n; placed++) {
            int bestNode = -1;
            double bestUB = Double.POSITIVE_INFINITY;
            for (int u = 0; u < n; u++) {
                if (!inOrder[u]) continue;
                for (int w : p.neighbors(u)) {
                    if (inOrder[w]) continue;
                    int la = Math.min(p.nodeLabel(u), p.nodeLabel(w));
                    int lb = Math.max(p.nodeLabel(u), p.nodeLabel(w));
                    long key = ((long) la << 42) | ((long) lb << 21) | p.edgeLabelBetween(u, w);
                    double ub = tw.getOrDefault(key, 0.0);
                    if (ub < bestUB - 1e-12 || (Math.abs(ub - bestUB) <= 1e-12 && (bestNode < 0 || w < bestNode))) {
                        bestUB = ub;
                        bestNode = w;
                    }
                }
            }
            order[placed] = bestNode;
            inOrder[bestNode] = true;
        }
        return order;
    }

    /**
     * Compute the exact W(p) = max over occurrences of (min edge ω) by binary search over
     * the descending distinct-weight set: W(p) = the largest value t such that p embeds in
     * G_{≥t}. {@code sortedDistinctDesc} is the distinct-weight array sorted DESCENDING.
     * Returns 0 if p does not embed at any level.
     */
    public double maxBottleneckWeight(Pattern p, double[] sortedDistinctDesc) {
        // binary search: embeddability is monotone-decreasing in t (larger t ⇒ fewer edges ⇒ harder)
        int lo = 0, hi = sortedDistinctDesc.length - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (embedsWithMinWeight(p, sortedDistinctDesc[mid])) {
                ans = mid;        // embeds at this high threshold ⇒ try even higher
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return ans < 0 ? 0.0 : sortedDistinctDesc[ans];
    }

    // ===================== VERTEX weight checks (OWGraMi) =====================

    /**
     * W_v(S) ≥ minVertexWeight ⟺ ∃ an embedding in which every assigned G vertex has
     * vertexWeight ≥ minVertexWeight. Serves OWGraMi (vertex bottleneck weight). Counts
     * one iso-call (the same unit as embedsWithMinWeight).
     */
    public boolean embedsWithMinVertexWeight(Pattern p, double minVertexWeight) {
        metrics.isoCallCount++;
        metrics.weightIsoCalls++; // CHEAP iso-call: vertex-weight check (OWGraMi)
        int n = p.nodeCount();
        int start = chooseStart(p);
        int targetLabel = p.nodeLabel(start);
        int patDeg = p.degree(start);
        int[] order = bfsOrder(p, start);

        int[] assign = new int[n]; // reused across all root vertices
        for (int v = 0; v < g.numVertices(); v++) {
            if (g.vertexLabel(v) != targetLabel) continue;
            if (g.neighbors(v).size() < patDeg) continue;
            if (g.vertexWeight(v) < minVertexWeight) continue;
            Arrays.fill(assign, -1);
            newSearch();
            assign[start] = v;
            usedStamp[v] = usedGen;
            if (backtrackVW(p, order, 1, assign, minVertexWeight)) return true;
        }
        return false;
    }

    /**
     * Backtracking for embedsWithMinVertexWeight: when choosing a G vertex for a pattern
     * variable, check vertexWeight ≥ minVW. Does not filter by EDGE weight (OWGraMi does
     * not use it).
     */
    private boolean backtrackVW(Pattern p, int[] matchOrder, int pos, int[] assign, double minVW) {
        metrics.backtrackNodes++;
        if (++searchNodes > searchBudget) { searchAborted = true; return false; } // budget (direction A)
        int n = p.nodeCount();
        if (pos == n) return true;

        int patNode = matchOrder[pos];
        int targetLabel = p.nodeLabel(patNode);

        int anchor = -1;
        for (int w : p.neighbors(patNode)) {
            if (assign[w] != -1) { anchor = w; break; }
        }
        List<LabeledWeightedGraph.Adjacency> anchorAdj = g.neighbors(assign[anchor]);
        int[] stamp = usedStamp;
        int gen = usedGen;
        for (LabeledWeightedGraph.Adjacency cand : anchorAdj) {
            int gv = cand.to;
            if (stamp[gv] == gen) continue;
            if (g.vertexLabel(gv) != targetLabel) continue;
            if (g.vertexWeight(gv) < minVW) continue;
            // no edge-weight filtering: OWGraMi cares only about vertex weights
            if (!consistent(p, patNode, gv, assign, Double.NEGATIVE_INFINITY)) continue;

            assign[patNode] = gv;
            stamp[gv] = gen;
            if (backtrackVW(p, matchOrder, pos + 1, assign, minVW)) return true;
            stamp[gv] = 0;
            assign[patNode] = -1;
        }
        return false;
    }

    /**
     * Choose the start pattern vertex via MULTI-CRITERIA VOTING {@link PivotVoter}:
     * combining domain size (a label-based proxy), pattern degree, and the weight upper
     * bound around the vertex. With {@link PivotVoter#DEGREE_ONLY} (the default) it
     * degenerates to the old "highest degree".
     */
    private int chooseStart(Pattern p) {
        int n = p.nodeCount();
        double[] domainSize = new double[n];
        int[] degree = new int[n];
        double[] weightUB = new double[n];
        Map<Integer, Integer> lc = labelCount();
        Map<Long, Double> tw = tripleMaxW();
        for (int v = 0; v < n; v++) {
            degree[v] = p.degree(v);
            domainSize[v] = lc.getOrDefault(p.nodeLabel(v), 0);
            // weight UB around v = min over incident edges of (the global max ω per edge type)
            double ub = Double.POSITIVE_INFINITY;
            for (int w : p.neighbors(v)) {
                int la = Math.min(p.nodeLabel(v), p.nodeLabel(w));
                int lb = Math.max(p.nodeLabel(v), p.nodeLabel(w));
                long key = ((long) la << 42) | ((long) lb << 21) | p.edgeLabelBetween(v, w);
                ub = Math.min(ub, tw.getOrDefault(key, 0.0));
            }
            weightUB[v] = ub;
        }
        return voter.choosePivot(domainSize, degree, weightUB);
    }

    /** Count of G vertices per label (domain-size proxy for PivotVoter); built once. */
    private Map<Integer, Integer> labelCount() {
        if (labelCountCache == null) {
            Map<Integer, Integer> m = new HashMap<>();
            for (int v = 0; v < g.numVertices(); v++) {
                m.merge(g.vertexLabel(v), 1, Integer::sum);
            }
            labelCountCache = m;
        }
        return labelCountCache;
    }

    /** Max ω per edge type (la,lb,el) — the weight signal for PivotVoter; built once. */
    private Map<Long, Double> tripleMaxW() {
        if (tripleMaxWCache == null) {
            Map<Long, Double> m = new HashMap<>();
            for (int u = 0; u < g.numVertices(); u++) {
                int lu = g.vertexLabel(u);
                for (LabeledWeightedGraph.Adjacency adj : g.neighbors(u)) {
                    if (adj.to <= u) continue;
                    int lv = g.vertexLabel(adj.to);
                    int la = Math.min(lu, lv), lb = Math.max(lu, lv);
                    long key = ((long) la << 42) | ((long) lb << 21) | adj.edgeLabel;
                    m.merge(key, adj.weight, Math::max);
                }
            }
            tripleMaxWCache = m;
        }
        return tripleMaxWCache;
    }
}
