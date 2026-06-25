package welt.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Undirected graph with integer-labeled vertices and edges carrying a structural
 * label (integer) AND a weight (positive real), kept separate.
 *
 * <p>Shared model for all four algorithms (GraMi / OWGraMi / WEGM / WeLT). Edges are
 * stored undirected: each edge appears in the adjacency lists of both endpoints.
 *
 * <p>Vertex ids are assumed contiguous in [0, numVertices). {@link LgGraphReader}
 * guarantees this by remapping ids when necessary.
 */
public final class LabeledWeightedGraph {

    /** One adjacency entry: destination vertex + structural label + weight. */
    public static final class Adjacency {
        public final int to;
        public final int edgeLabel;
        public final double weight;

        public Adjacency(int to, int edgeLabel, double weight) {
            this.to = to;
            this.edgeLabel = edgeLabel;
            this.weight = weight;
        }
    }

    private final int[] vertexLabels;          // vertexLabels[v] = label of vertex v
    private final double[] vertexWeights;      // vertexWeights[v] = vertex weight (for OWGraMi)
    private final List<List<Adjacency>> adj;   // undirected adjacency lists
    private int numUndirectedEdges = 0;

    public LabeledWeightedGraph(int numVertices) {
        this.vertexLabels = new int[numVertices];
        this.vertexWeights = new double[numVertices];
        this.adj = new ArrayList<>(numVertices);
        for (int i = 0; i < numVertices; i++) {
            adj.add(new ArrayList<>());
        }
    }

    public int numVertices() {
        return vertexLabels.length;
    }

    public int numEdges() {
        return numUndirectedEdges;
    }

    public void setVertexLabel(int v, int label) {
        vertexLabels[v] = label;
    }

    public int vertexLabel(int v) {
        return vertexLabels[v];
    }

    public void setVertexWeight(int v, double w) {
        vertexWeights[v] = w;
    }

    /** Vertex weight (default 0). Used for OWGraMi (vertex weights). */
    public double vertexWeight(int v) {
        return vertexWeights[v];
    }

    /**
     * Assign a weight to every vertex via {@link VertexWeightAssigner} (computed from
     * the incident edges). Call after all edges have been loaded.
     */
    public void assignVertexWeights(VertexWeightAssigner assigner) {
        for (int v = 0; v < vertexLabels.length; v++) {
            vertexWeights[v] = assigner.weightOf(v, adj.get(v));
        }
    }

    public List<Adjacency> neighbors(int v) {
        return adj.get(v);
    }

    // O(1) edge index (built lazily): key (min<<32|max) -> Adjacency. Speeds up edge
    // existence checks in backtracking (consistent/graphHasEdge) — a hotspot on dense graphs.
    private java.util.Map<Long, Adjacency> edgeIndex;

    /** Returns the undirected edge (u,v), or {@code null} if absent. O(1) on average. */
    public Adjacency edge(int u, int v) {
        if (edgeIndex == null) {
            java.util.Map<Long, Adjacency> m = new java.util.HashMap<>(Math.max(16, numUndirectedEdges * 2));
            for (int a = 0; a < adj.size(); a++) {
                for (Adjacency e : adj.get(a)) {
                    if (a < e.to) m.put(edgeKey(a, e.to), e);
                }
            }
            edgeIndex = m;
        }
        return edgeIndex.get(edgeKey(u, v));
    }

    private static long edgeKey(int u, int v) {
        int lo = Math.min(u, v), hi = Math.max(u, v);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }

    /**
     * Add an undirected edge (u,v). The caller is responsible for deduplication
     * (see {@link LgGraphReader}); here we simply append to both adjacency lists.
     */
    public void addUndirectedEdge(int u, int v, int edgeLabel, double weight) {
        adj.get(u).add(new Adjacency(v, edgeLabel, weight));
        adj.get(v).add(new Adjacency(u, edgeLabel, weight));
        numUndirectedEdges++;
    }
}
