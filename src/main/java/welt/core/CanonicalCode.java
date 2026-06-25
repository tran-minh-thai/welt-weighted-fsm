package welt.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Structural canonical code for a {@link Pattern}: two isomorphic patterns (same vertex
 * labels + edge labels, invariant under vertex order) ⇔ the same code. Used to
 * deduplicate patterns during the extension loop.
 *
 * <p><b>Implementation (Milestone 5): gSpan-style MINIMUM DFS CODE</b> (Yan &amp; Han,
 * ICDM 2002). A <em>DFS code</em> is a sequence of edge quintuples
 * {@code (i,j,l_i,l_e,l_j)} in the order of a DFS traversal, where {@code i,j} are
 * vertex discovery timestamps. The MINIMUM DFS CODE (under gSpan's lexicographic order
 * on the quintuple sequence) is an ISOMORPHISM INVARIANT: isomorphic graphs share the
 * same minimum code, non-isomorphic ones differ.
 *
 * <p>Unlike the old version (brute force over {@code n!} permutations, capped at
 * {@code n≤9}), this version finds the minimum code by extending along the "rightmost
 * path" and keeping ONLY the states that reach the minimum prefix at each step. The cost
 * is bounded by the pattern's automorphism count (very small for labeled patterns)
 * instead of {@code n!} ⇒ NO more {@code n≤9} cap, able to handle large patterns at low
 * support thresholds.
 *
 * <p><b>Why it is correct.</b> (1) gSpan's rightmost-path extension rule generates
 * EXACTLY the set of all valid DFS codes of the graph; for two isomorphic graphs these
 * sets coincide (as label sequences) ⇒ the minimum codes are equal ⇒ canonical. (2) In
 * gSpan's quintuple order, a BACKWARD edge from the rightmost vertex is always smaller
 * than a FORWARD edge ⇒ keeping only the minimum prefix never "overlooks" a backward
 * edge (no branch gets stuck) ⇒ every surviving state extends to a complete code.
 * Cross-checked against the brute-force version (identical isomorphism-class partition)
 * in {@code CanonicalCodeGSpanTest}.
 *
 * <p><b>Preventing explosion on symmetric patterns.</b> If we kept EVERY minimum-prefix
 * state, a highly symmetric pattern (a same-label star/clique) would spawn up to
 * {@code k!} automorphic states. We DEDUPLICATE EQUIVALENT states via an ISOMORPHISM-
 * INVARIANT signature built from the 1-WL color refinement ({@link #wlColors}). The key
 * point: to be canonical, it suffices to make a DETERMINISTIC and ISOMORPHISM-INVARIANT
 * choice (isomorphic graphs ⇒ same decision) that yields a COMPLETE DFS code — not
 * necessarily the absolute minimum. Since a complete code determines the graph up to
 * isomorphism, equal codes ⇒ isomorphic graphs. Deduplication by the WL signature
 * preserves isomorphism invariance, so the result is still canonical.
 *
 * <p><b>Practical assumption:</b> 1-WL distinguishes every automorphism orbit of the FSM
 * patterns arising on real labeled graphs (citeseer/mico). The 1-WL counterexamples
 * (unlabeled strongly regular graphs, …) do not appear as frequent subpatterns here. The
 * brute-force cross-check on stars/cliques (n≤9) in {@code CanonicalCodeGSpanTest} confirms this.
 */
public final class CanonicalCode {

    /** Safety threshold of the BRUTE-FORCE version (now used only for cross-validation). */
    public static final int MAX_NODES_FOR_BRUTE_FORCE = 9;

    private CanonicalCode() {
    }

    public static String of(Pattern p) {
        return minDfsCode(p);
    }

    // ===================== MINIMUM DFS CODE (gSpan) =====================

    /** DFS-code construction state: timestamp ↔ graph-vertex mapping + the current code. */
    private static final class St {
        final int[] tsToG;       // timestamp -> graph vertex (assigned portion only)
        final int[] gToTs;       // graph vertex -> timestamp (or -1)
        final int[] parent;      // timestamp -> parent in the DFS tree (-1 = root)
        final boolean[] usedEdge;
        final int[] code;        // flat: 5 ints per edge (i,j,li,le,lj)
        int codeLen;             // number of edges placed
        int assigned;            // number of vertices that have been timestamped

        St(int n, int m) {
            tsToG = new int[n];
            gToTs = new int[n];
            Arrays.fill(gToTs, -1);
            parent = new int[n];
            usedEdge = new boolean[m];
            code = new int[5 * m];
            codeLen = 0;
            assigned = 0;
        }

        St copy() {
            St s = new St(tsToG.length, usedEdge.length);
            System.arraycopy(tsToG, 0, s.tsToG, 0, assigned);
            System.arraycopy(gToTs, 0, s.gToTs, 0, gToTs.length);
            System.arraycopy(parent, 0, s.parent, 0, assigned);
            System.arraycopy(usedEdge, 0, s.usedEdge, 0, usedEdge.length);
            System.arraycopy(code, 0, s.code, 0, 5 * codeLen);
            s.codeLen = codeLen;
            s.assigned = assigned;
            return s;
        }

        /**
         * ISOMORPHISM-INVARIANT signature for deduplicating EQUIVALENT states (not merely
         * identical ones): for each timestamp in order, take the vertex's WL color + the
         * multiset {@code (edge label, WL color)} of its UNASSIGNED neighbors. Two states
         * with the same code that are automorphic (e.g. choosing different leaves of the
         * same star) have the same signature ⇒ collapse to one ⇒ prevents the {@code k!}
         * explosion. Isomorphism-invariant, so canonicity is preserved.
         */
        String isoSignature(List<List<int[]>> adj, int[] wl) {
            StringBuilder sb = new StringBuilder();
            for (int t = 0; t < assigned; t++) {
                int v = tsToG[t];
                sb.append(wl[v]).append('#');
                List<long[]> nbs = new ArrayList<>();
                for (int[] e : adj.get(v)) {
                    if (gToTs[e[0]] == -1) nbs.add(new long[]{e[2], wl[e[0]]});
                }
                nbs.sort((x, y) -> x[0] != y[0] ? Long.compare(x[0], y[0]) : Long.compare(x[1], y[1]));
                for (long[] e : nbs) sb.append(e[0]).append(',').append(e[1]).append(';');
                sb.append('|');
            }
            return sb.toString();
        }
    }

    static String minDfsCode(Pattern p) {
        int n = p.nodeCount();
        List<int[]> edgeList = p.edges(); // {u,v,el}, u<v
        int m = edgeList.size();
        if (m == 0) {
            // edgeless pattern: the invariant = the sorted multiset of vertex labels
            int[] labels = new int[n];
            for (int i = 0; i < n; i++) labels[i] = p.nodeLabel(i);
            Arrays.sort(labels);
            return "N0" + Arrays.toString(labels);
        }

        // adjacency list with edge indices: adj[v] = list of {neighbor, edgeIndex, edgeLabel}
        List<List<int[]>> adj = new ArrayList<>(n);
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (int idx = 0; idx < m; idx++) {
            int[] e = edgeList.get(idx);
            adj.get(e[0]).add(new int[]{e[1], idx, e[2]});
            adj.get(e[1]).add(new int[]{e[0], idx, e[2]});
        }

        // 1-WL color refinement — ISOMORPHISM-INVARIANT — to deduplicate equivalent states,
        // preventing the explosion on symmetric patterns (same-label star/clique). See class note.
        int[] wl = wlColors(p, adj);

        // ---- Initial states: each edge, both directions, as the first edge (0,1) ----
        List<St> states = new ArrayList<>();
        for (int idx = 0; idx < m; idx++) {
            int[] e = edgeList.get(idx);
            states.add(initialState(p, n, m, e[0], e[1], idx, e[2]));
            states.add(initialState(p, n, m, e[1], e[0], idx, e[2]));
        }
        states = keepMinTuple(states, adj, wl);

        // ---- Extend until all m edges are placed, always keeping the minimum prefix ----
        while (states.get(0).codeLen < m) {
            List<St> grown = new ArrayList<>();
            for (St st : states) {
                for (int[] ext : feasibleExtensions(p, adj, st)) {
                    grown.add(applyExtension(st, ext));
                }
            }
            states = keepMinTuple(grown, adj, wl);
        }
        return encode(states.get(0).code, m);
    }

    /**
     * 1-WL color refinement: initialize with vertex labels, iteratively refine by the
     * multiset {@code (edge label, neighbor color)} until a fixed point. The colors are
     * ISOMORPHISM-INVARIANT and canonically numbered (signatures ranked lexicographically)
     * ⇒ isomorphic graphs yield the same coloring. Used as the signature for deduplicating
     * equivalent states during code search.
     */
    private static int[] wlColors(Pattern p, List<List<int[]>> adj) {
        int n = p.nodeCount();
        int[] color = new int[n];
        for (int v = 0; v < n; v++) color[v] = p.nodeLabel(v);
        int distinct = countDistinct(color);
        for (int iter = 0; iter < n; iter++) {
            String[] sig = new String[n];
            for (int v = 0; v < n; v++) {
                List<long[]> nbs = new ArrayList<>();
                for (int[] e : adj.get(v)) nbs.add(new long[]{e[2], color[e[0]]});
                nbs.sort((x, y) -> x[0] != y[0] ? Long.compare(x[0], y[0]) : Long.compare(x[1], y[1]));
                StringBuilder sb = new StringBuilder().append(color[v]).append(':');
                for (long[] e : nbs) sb.append(e[0]).append(',').append(e[1]).append(';');
                sig[v] = sb.toString();
            }
            java.util.TreeMap<String, Integer> rank = new java.util.TreeMap<>();
            for (String s : sig) rank.putIfAbsent(s, 0);
            int r = 0;
            for (var en : rank.entrySet()) en.setValue(r++);
            int[] next = new int[n];
            for (int v = 0; v < n; v++) next[v] = rank.get(sig[v]);
            int nd = rank.size();
            color = next;
            if (nd == distinct) break; // stable
            distinct = nd;
        }
        return color;
    }

    private static int countDistinct(int[] a) {
        Set<Integer> s = new HashSet<>();
        for (int x : a) s.add(x);
        return s.size();
    }

    private static St initialState(Pattern p, int n, int m, int a, int b, int edgeIdx, int el) {
        St s = new St(n, m);
        s.tsToG[0] = a;
        s.tsToG[1] = b;
        s.gToTs[a] = 0;
        s.gToTs[b] = 1;
        s.parent[0] = -1;
        s.parent[1] = 0;
        s.assigned = 2;
        s.usedEdge[edgeIdx] = true;
        writeTuple(s.code, 0, 0, 1, p.nodeLabel(a), el, p.nodeLabel(b));
        s.codeLen = 1;
        return s;
    }

    /**
     * The feasible extensions from a state under gSpan's rightmost-path rule: a BACKWARD
     * edge from the rightmost vertex to an ancestor on the rightmost path, and a FORWARD
     * edge from a vertex on the rightmost path to a new vertex. Returns a list of
     * {@code {i,j,li,le,lj, edgeIdx, fwdVertex}} (fwdVertex=-1 if it is a backward edge).
     */
    private static List<int[]> feasibleExtensions(Pattern p, List<List<int[]>> adj, St st) {
        int rmTs = st.assigned - 1;          // rightmost vertex = largest timestamp
        int gRm = st.tsToG[rmTs];
        // rightmost path: rmTs -> ... -> 0 following parents
        List<Integer> rmPath = new ArrayList<>();
        for (int t = rmTs; t != -1; t = st.parent[t]) rmPath.add(t);

        List<int[]> exts = new ArrayList<>();
        // BACKWARD edge from the rightmost vertex to an ancestor on the rightmost path
        for (int t : rmPath) {
            if (t == rmTs) continue;
            int gT = st.tsToG[t];
            int idx = edgeIndexBetween(adj, gRm, gT);
            if (idx >= 0 && !st.usedEdge[idx]) {
                int el = edgeLabel(adj, gRm, gT);
                exts.add(new int[]{rmTs, t, p.nodeLabel(gRm), el, p.nodeLabel(gT), idx, -1});
            }
        }
        // FORWARD edge from each vertex on the rightmost path to a new vertex
        for (int t : rmPath) {
            int gT = st.tsToG[t];
            for (int[] nb : adj.get(gT)) {
                int gw = nb[0], idx = nb[1], el = nb[2];
                if (st.gToTs[gw] == -1 && !st.usedEdge[idx]) {
                    exts.add(new int[]{t, st.assigned, p.nodeLabel(gT), el, p.nodeLabel(gw), idx, gw});
                }
            }
        }
        return exts;
    }

    private static St applyExtension(St st, int[] ext) {
        St s = st.copy();
        int idx = ext[5], fwdVertex = ext[6];
        s.usedEdge[idx] = true;
        writeTuple(s.code, s.codeLen, ext[0], ext[1], ext[2], ext[3], ext[4]);
        s.codeLen++;
        if (fwdVertex >= 0) {
            int ts = s.assigned;
            s.tsToG[ts] = fwdVertex;
            s.gToTs[fwdVertex] = ts;
            s.parent[ts] = ext[0];
            s.assigned++;
        }
        return s;
    }

    /**
     * Keep the states whose last quintuple is SMALLEST (in gSpan order), deduplicated.
     * When called on the initial states, the "last quintuple" is the first edge.
     */
    private static List<St> keepMinTuple(List<St> states, List<List<int[]>> adj, int[] wl) {
        int[] best = null;
        for (St s : states) {
            int[] last = lastTuple(s);
            if (best == null || compareTuple(last, best) < 0) best = last;
        }
        List<St> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (St s : states) {
            if (compareTuple(lastTuple(s), best) == 0 && seen.add(s.isoSignature(adj, wl))) kept.add(s);
        }
        return kept;
    }

    private static int[] lastTuple(St s) {
        int off = 5 * (s.codeLen - 1);
        return new int[]{s.code[off], s.code[off + 1], s.code[off + 2], s.code[off + 3], s.code[off + 4]};
    }

    /**
     * gSpan's DFS quintuple order: first by timestamp (i,j) with the forward/backward
     * rule, then by labels (l_i,l_e,l_j). The timestamp rule guarantees that a BACKWARD
     * edge from the rightmost vertex always precedes a FORWARD edge ⇒ no branch gets stuck.
     */
    static int compareTuple(int[] e1, int[] e2) {
        int t = compareTimestamp(e1, e2);
        if (t != 0) return t;
        // same (i,j): compare labels
        if (e1[2] != e2[2]) return Integer.compare(e1[2], e2[2]);
        if (e1[3] != e2[3]) return Integer.compare(e1[3], e2[3]);
        return Integer.compare(e1[4], e2[4]);
    }

    private static int compareTimestamp(int[] e1, int[] e2) {
        if (e1[0] == e2[0] && e1[1] == e2[1]) return 0;
        boolean f1 = e1[0] < e1[1]; // forward
        boolean f2 = e2[0] < e2[1];
        if (f1 && f2) {
            if (e1[1] != e2[1]) return Integer.compare(e1[1], e2[1]); // smaller j first
            return Integer.compare(e2[0], e1[0]);                     // larger i first
        }
        if (!f1 && !f2) {
            if (e1[0] != e2[0]) return Integer.compare(e1[0], e2[0]); // smaller i first
            return Integer.compare(e1[1], e2[1]);                     // smaller j first
        }
        if (f1) { // e1 forward, e2 backward: e1 precedes iff j1 ≤ i2
            return (e1[1] <= e2[0]) ? -1 : 1;
        }
        // e1 backward, e2 forward: e1 precedes iff i1 < j2
        return (e1[0] < e2[1]) ? -1 : 1;
    }

    private static void writeTuple(int[] code, int edgePos, int i, int j, int li, int le, int lj) {
        int off = 5 * edgePos;
        code[off] = i;
        code[off + 1] = j;
        code[off + 2] = li;
        code[off + 3] = le;
        code[off + 4] = lj;
    }

    private static String encode(int[] code, int m) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < m; k++) {
            int off = 5 * k;
            sb.append(code[off]).append(' ').append(code[off + 1]).append(' ')
                    .append(code[off + 2]).append(' ').append(code[off + 3]).append(' ')
                    .append(code[off + 4]).append(';');
        }
        return sb.toString();
    }

    private static int edgeIndexBetween(List<List<int[]>> adj, int a, int b) {
        for (int[] nb : adj.get(a)) if (nb[0] == b) return nb[1];
        return -1;
    }

    private static int edgeLabel(List<List<int[]>> adj, int a, int b) {
        for (int[] nb : adj.get(a)) if (nb[0] == b) return nb[2];
        return -1;
    }

    // ===================== BRUTE-FORCE version (cross-validation only) =====================

    /**
     * Canonical code by brute force over {@code n!} permutations — SLOW but obviously
     * correct, used in tests to confirm that the minimum DFS code partitions isomorphism
     * classes identically. Throws if {@code n > MAX_NODES_FOR_BRUTE_FORCE}.
     */
    static String bruteForceOf(Pattern p) {
        int n = p.nodeCount();
        if (n > MAX_NODES_FOR_BRUTE_FORCE) {
            throw new IllegalStateException("Brute force is only for n ≤ " + MAX_NODES_FOR_BRUTE_FORCE);
        }
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) perm[i] = i;
        String[] best = {null};
        permute(p, perm, 0, best);
        return best[0];
    }

    private static void permute(Pattern p, int[] perm, int k, String[] best) {
        int n = perm.length;
        if (k == n) {
            String s = bruteEncode(p, perm);
            if (best[0] == null || s.compareTo(best[0]) < 0) best[0] = s;
            return;
        }
        for (int i = k; i < n; i++) {
            swap(perm, k, i);
            permute(p, perm, k + 1, best);
            swap(perm, k, i);
        }
    }

    private static String bruteEncode(Pattern p, int[] perm) {
        int n = p.nodeCount();
        int[] labelAtNewPos = new int[n];
        for (int old = 0; old < n; old++) labelAtNewPos[perm[old]] = p.nodeLabel(old);
        StringBuilder sb = new StringBuilder();
        sb.append('N');
        for (int pos = 0; pos < n; pos++) sb.append(labelAtNewPos[pos]).append(',');
        long[] enc = new long[p.edgeCount()];
        int ei = 0;
        for (int u = 0; u < n; u++) {
            for (int v = u + 1; v < n; v++) {
                if (p.hasEdge(u, v)) {
                    int a = perm[u], b = perm[v];
                    int lo = Math.min(a, b), hi = Math.max(a, b);
                    int el = p.edgeLabelBetween(u, v);
                    enc[ei++] = (((long) lo) << 40) | (((long) hi) << 20) | (el & 0xFFFFF);
                }
            }
        }
        Arrays.sort(enc);
        sb.append('E');
        for (long e : enc) sb.append(e).append(',');
        return sb.toString();
    }

    private static void swap(int[] a, int i, int j) {
        int t = a[i];
        a[i] = a[j];
        a[j] = t;
    }
}
