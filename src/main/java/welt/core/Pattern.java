package welt.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A small PATTERN (query subgraph): an undirected graph with vertex labels and
 * structural edge labels. Immutable — every extension produces a new {@code Pattern}.
 * Since FSM patterns are typically small, we store an {@code n×n} edge-label matrix
 * ({@code -1} = no edge) for convenient matching.
 *
 * <p>Note: this is a PATTERN structure, distinct from the large data graph
 * {@link LabeledWeightedGraph}. Patterns carry no weight in the GraMi baseline; weight
 * appears only in the weighted strategies (WEGM/WeLT) through later extensions.
 */
public final class Pattern {

    private final int[] nodeLabels;     // pattern vertex labels, indices 0..n-1
    private final int[][] edgeLabel;    // symmetric edge-label matrix, -1 if no edge
    private final int edgeCount;

    private Pattern(int[] nodeLabels, int[][] edgeLabel, int edgeCount) {
        this.nodeLabels = nodeLabels;
        this.edgeLabel = edgeLabel;
        this.edgeCount = edgeCount;
    }

    /**
     * Build a connected pattern from vertex labels + an edge list {@code {u,v,el}}.
     * Convenient for tests and for parsing oracle patterns. Requires vertex indices
     * in [0,n).
     */
    public static Pattern of(int[] nodeLabels, int[][] edgeList) {
        int n = nodeLabels.length;
        int[][] em = new int[n][n];
        for (int[] row : em) java.util.Arrays.fill(row, -1);
        int cnt = 0;
        for (int[] e : edgeList) {
            int u = e[0], v = e[1], el = e[2];
            if (em[u][v] < 0) cnt++;
            em[u][v] = el;
            em[v][u] = el;
        }
        return new Pattern(nodeLabels.clone(), em, cnt);
    }

    /** Single-edge pattern: two vertices labeled {@code labelA},{@code labelB} joined by an edge labeled {@code el}. */
    public static Pattern singleEdge(int labelA, int labelB, int el) {
        int[] labels = {labelA, labelB};
        int[][] em = {{-1, el}, {el, -1}};
        return new Pattern(labels, em, 1);
    }

    public int nodeCount() {
        return nodeLabels.length;
    }

    public int edgeCount() {
        return edgeCount;
    }

    public int nodeLabel(int v) {
        return nodeLabels[v];
    }

    /** Edge label between u,v, or {@code -1} if there is no edge. */
    public int edgeLabelBetween(int u, int v) {
        return edgeLabel[u][v];
    }

    public boolean hasEdge(int u, int v) {
        return edgeLabel[u][v] >= 0;
    }

    public int degree(int v) {
        int d = 0;
        for (int j = 0; j < nodeLabels.length; j++) {
            if (edgeLabel[v][j] >= 0) d++;
        }
        return d;
    }

    /** List of v's adjacent vertices in the pattern. */
    public int[] neighbors(int v) {
        int d = degree(v);
        int[] res = new int[d];
        int k = 0;
        for (int j = 0; j < nodeLabels.length; j++) {
            if (edgeLabel[v][j] >= 0) res[k++] = j;
        }
        return res;
    }

    /** Edge list {u,v,el} with u&lt;v (each edge once). */
    public List<int[]> edges() {
        List<int[]> es = new ArrayList<>(edgeCount);
        for (int u = 0; u < nodeLabels.length; u++) {
            for (int v = u + 1; v < nodeLabels.length; v++) {
                if (edgeLabel[u][v] >= 0) es.add(new int[]{u, v, edgeLabel[u][v]});
            }
        }
        return es;
    }

    /**
     * Create a new pattern by adding a LEAF: a new vertex (label {@code newLabel})
     * joined to the existing vertex {@code u} by an edge labeled {@code el}.
     */
    public Pattern withLeaf(int u, int newLabel, int el) {
        int n = nodeLabels.length;
        int[] labels = new int[n + 1];
        System.arraycopy(nodeLabels, 0, labels, 0, n);
        labels[n] = newLabel;
        int[][] em = new int[n + 1][n + 1];
        for (int[] row : em) java.util.Arrays.fill(row, -1);
        for (int i = 0; i < n; i++) {
            System.arraycopy(edgeLabel[i], 0, em[i], 0, n);
        }
        em[u][n] = el;
        em[n][u] = el;
        return new Pattern(labels, em, edgeCount + 1);
    }

    /**
     * Create a new pattern by adding a CHORD: an edge labeled {@code el} between the
     * two existing vertices {@code u},{@code w} (not yet adjacent).
     */
    public Pattern withChord(int u, int w, int el) {
        int n = nodeLabels.length;
        int[][] em = new int[n][n];
        for (int i = 0; i < n; i++) em[i] = edgeLabel[i].clone();
        em[u][w] = el;
        em[w][u] = el;
        return new Pattern(nodeLabels.clone(), em, edgeCount + 1);
    }

    /** GraMi-style .lg-like format for printing / oracle comparison. */
    public String toLgString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nodeLabels.length; i++) {
            sb.append("v ").append(i).append(' ').append(nodeLabels[i]).append('\n');
        }
        for (int[] e : edges()) {
            sb.append("e ").append(e[0]).append(' ').append(e[1]).append(' ').append(e[2]).append('\n');
        }
        return sb.toString();
    }

    /**
     * All CONNECTED subpatterns with {@code (edgeCount-1)} edges, generated by removing
     * EACH edge in turn. Removing an edge may (a) leave a vertex isolated (degree 0) —
     * in which case that vertex is dropped and the rest renumbered (the inverse of
     * {@link #withLeaf}); or (b) keep the vertex count if the edge lies on a cycle (the
     * inverse of {@link #withChord}). If removing the edge DISCONNECTS the graph, that
     * subpattern is SKIPPED (it is not part of the connected pattern lattice).
     *
     * <p>Used for ANTI-MONOTONE pruning: if a connected {@code (k-1)}-edge subpattern is
     * NOT frequent then the {@code k}-edge pattern is not frequent either. Duplicates
     * (isomorphic) may be produced — the caller deduplicates via the canonical code.
     */
    public List<Pattern> connectedEdgeDeletedSubpatterns() {
        List<Pattern> subs = new ArrayList<>();
        List<int[]> es = edges();
        int n = nodeLabels.length;
        for (int r = 0; r < es.size(); r++) {
            int[] deg = new int[n];
            List<int[]> rem = new ArrayList<>(es.size() - 1);
            for (int i = 0; i < es.size(); i++) {
                if (i == r) continue;
                int[] e = es.get(i);
                rem.add(e);
                deg[e[0]]++;
                deg[e[1]]++;
            }
            // keep vertices with degree ≥ 1; renumber
            int[] newIdx = new int[n];
            java.util.Arrays.fill(newIdx, -1);
            int kept = 0;
            for (int v = 0; v < n; v++) if (deg[v] > 0) newIdx[v] = kept++;
            if (kept == 0) continue;
            int[] labelsNew = new int[kept];
            for (int v = 0; v < n; v++) if (newIdx[v] >= 0) labelsNew[newIdx[v]] = nodeLabels[v];

            int[][] edgeListNew = new int[rem.size()][];
            List<List<Integer>> adj = new ArrayList<>();
            for (int i = 0; i < kept; i++) adj.add(new ArrayList<>());
            for (int i = 0; i < rem.size(); i++) {
                int[] e = rem.get(i);
                int a = newIdx[e[0]], b = newIdx[e[1]];
                edgeListNew[i] = new int[]{a, b, e[2]};
                adj.get(a).add(b);
                adj.get(b).add(a);
            }
            // connectivity check (BFS) over the kept vertices
            boolean[] seen = new boolean[kept];
            java.util.Deque<Integer> q = new java.util.ArrayDeque<>();
            q.add(0);
            seen[0] = true;
            int cnt = 1;
            while (!q.isEmpty()) {
                int c = q.poll();
                for (int w : adj.get(c)) if (!seen[w]) { seen[w] = true; cnt++; q.add(w); }
            }
            if (cnt != kept) continue; // edge removal disconnects ⇒ not in the connected lattice
            subs.add(Pattern.of(labelsNew, edgeListNew));
        }
        return subs;
    }

    @Override
    public String toString() {
        return "Pattern(n=" + nodeLabels.length + ",e=" + edgeCount + "){" + CanonicalCode.of(this) + "}";
    }
}
