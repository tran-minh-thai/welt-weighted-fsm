package welt.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reader for GraMi's .lg format for UNDIRECTED WEIGHTED graphs.
 *
 * <p>Format (verified in practice on citeseer.lg):
 * <pre>
 *   t # 1                 // transaction header line (ignored)
 *   v &lt;id&gt; &lt;vertex_label&gt;  // vertex label is an integer
 *   e &lt;src&gt; &lt;dst&gt; &lt;value&gt;  // the 3rd token is a REAL number (similarity)
 * </pre>
 *
 * <p>The graph is treated as undirected: if the file contains both directed edges
 * (u,v) and (v,u), they are merged into ONE undirected edge (recorded in {@link Stats}).
 *
 * <p>The 3rd token on an edge line is interpreted as a "raw value" and passed through
 * two separate configurable functions: {@link EdgeLabelAssigner} (the structural label
 * for isomorphism matching) and {@link WeightAssigner} (the weight for pruning rules).
 * For CiteSeer the structural label is constant (an edge-unlabeled graph) and the
 * weight equals the raw value itself.
 */
public final class LgGraphReader {

    private final WeightAssigner weightAssigner;
    private final EdgeLabelAssigner edgeLabelAssigner;
    private final VertexWeightAssigner vertexWeightAssigner;

    /** Uses the default CiteSeer convention: unlabeled edges, weight = raw value. */
    public LgGraphReader() {
        this(WeightAssigner.IDENTITY, EdgeLabelAssigner.CONSTANT_UNLABELED,
                VertexWeightAssigner.AVERAGE_INCIDENT_EDGE);
    }

    public LgGraphReader(WeightAssigner weightAssigner, EdgeLabelAssigner edgeLabelAssigner) {
        this(weightAssigner, edgeLabelAssigner, VertexWeightAssigner.AVERAGE_INCIDENT_EDGE);
    }

    public LgGraphReader(WeightAssigner weightAssigner, EdgeLabelAssigner edgeLabelAssigner,
                         VertexWeightAssigner vertexWeightAssigner) {
        this.weightAssigner = weightAssigner;
        this.edgeLabelAssigner = edgeLabelAssigner;
        this.vertexWeightAssigner = vertexWeightAssigner;
    }

    /** Read result: the graph plus an attached statistics object. */
    public static final class Result {
        public final LabeledWeightedGraph graph;
        public final Stats stats;

        Result(LabeledWeightedGraph graph, Stats stats) {
            this.graph = graph;
            this.stats = stats;
        }
    }

    /** Statistics and warnings collected during reading. */
    public static final class Stats {
        public int numVertices;
        public int numDirectedEdgeLines;   // number of 'e' lines read (directed)
        public int numUndirectedEdges;     // number of undirected edges after merging
        public int reciprocalMerged;       // number of (u,v)&(v,u) pairs merged
        public int parallelDropped;        // number of duplicate parallel edges dropped
        public int conflictingWeights;     // number of duplicate edges with DIFFERENT weights
        public int selfLoops;              // number of self-loop edges
        public int nonPositiveWeights;     // number of edges with weight <= 0
        public final TreeSet<Integer> vertexLabels = new TreeSet<>();
        public final TreeSet<Integer> edgeLabels = new TreeSet<>();
        public double minWeight = Double.POSITIVE_INFINITY;
        public double maxWeight = Double.NEGATIVE_INFINITY;
        public double sumWeight = 0.0;
        public boolean idsRemapped = false;
        public final List<String> warnings = new ArrayList<>();

        public double meanWeight() {
            return numUndirectedEdges == 0 ? 0.0 : sumWeight / numUndirectedEdges;
        }
    }

    public Result read(Path file) throws IOException {
        // ---- Pass 1: collect vertices to learn the count and id -> dense-index map ----
        // (temporary structures; edges are processed in pass 2)
        List<int[]> vertexDecls = new ArrayList<>();   // [originalId, label]
        List<double[]> edgeDecls = new ArrayList<>();   // [src, dst, rawValue]

        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                line = line.trim();
                if (line.isEmpty()) continue;
                char c = line.charAt(0);
                if (c == 't' || c == '#') continue; // transaction header / comment
                String[] p = line.split("\\s+");
                if (c == 'v') {
                    if (p.length < 3) {
                        throw new IOException("Malformed v line at line " + lineNo + ": " + line);
                    }
                    vertexDecls.add(new int[]{Integer.parseInt(p[1]), Integer.parseInt(p[2])});
                } else if (c == 'e') {
                    if (p.length < 4) {
                        throw new IOException("Malformed e line at line " + lineNo + ": " + line);
                    }
                    edgeDecls.add(new double[]{
                            Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3])});
                }
                // other line types: ignored
            }
        }

        Stats stats = new Stats();

        // ---- Map original ids -> dense indices [0, n) ----
        Map<Integer, Integer> idMap = new HashMap<>();
        int next = 0;
        boolean contiguous = true;
        for (int[] vd : vertexDecls) {
            int origId = vd[0];
            if (!idMap.containsKey(origId)) {
                if (origId != next) contiguous = false;
                idMap.put(origId, next++);
            }
        }
        stats.idsRemapped = !contiguous;
        stats.numVertices = idMap.size();

        LabeledWeightedGraph graph = new LabeledWeightedGraph(stats.numVertices);
        for (int[] vd : vertexDecls) {
            int dense = idMap.get(vd[0]);
            graph.setVertexLabel(dense, vd[1]);
            stats.vertexLabels.add(vd[1]);
        }

        // ---- Pass 2: add undirected edges, merging duplicates ----
        // undirected key = (min<<32 | max) over dense indices
        Map<Long, Double> seenWeight = new HashMap<>();
        Set<Long> seenDirected = new HashSet<>();
        for (double[] ed : edgeDecls) {
            stats.numDirectedEdgeLines++;
            int src = idMap.get((int) ed[0]);
            int dst = idMap.get((int) ed[1]);
            double raw = ed[2];

            if (src == dst) {
                stats.selfLoops++;
                stats.warnings.add("Self-loop at vertex " + (int) ed[0] + " (skipped).");
                continue;
            }

            double weight = weightAssigner.weightOf((int) ed[0], (int) ed[1], raw);
            int edgeLabel = edgeLabelAssigner.labelOf((int) ed[0], (int) ed[1], raw);

            long directedKey = ((long) src << 32) | (dst & 0xffffffffL);
            if (seenDirected.contains(directedKey)) {
                stats.parallelDropped++; // exact duplicate directed edge (rare)
                continue;
            }
            seenDirected.add(directedKey);

            int a = Math.min(src, dst), b = Math.max(src, dst);
            long undirectedKey = ((long) a << 32) | (b & 0xffffffffL);
            Double prev = seenWeight.get(undirectedKey);
            if (prev != null) {
                // undirected edge (a,b) already exists -> this is the reverse direction: merge
                stats.reciprocalMerged++;
                if (Math.abs(prev - weight) > 1e-9) {
                    stats.conflictingWeights++;
                    stats.warnings.add("Edge (" + (int) ed[0] + "," + (int) ed[1]
                            + ") has a different weight than its reverse (" + prev + " vs " + weight
                            + "); keeping the first value.");
                }
                continue; // do not add a second edge
            }

            seenWeight.put(undirectedKey, weight);
            graph.addUndirectedEdge(src, dst, edgeLabel, weight);
            stats.edgeLabels.add(edgeLabel);

            if (weight <= 0.0) {
                stats.nonPositiveWeights++;
            }
            stats.minWeight = Math.min(stats.minWeight, weight);
            stats.maxWeight = Math.max(stats.maxWeight, weight);
            stats.sumWeight += weight;
        }

        stats.numUndirectedEdges = graph.numEdges();
        if (stats.nonPositiveWeights > 0) {
            stats.warnings.add(stats.nonPositiveWeights + " edges have weight <= 0; "
                    + "WeLT assumes positive weights — a handling decision is needed.");
        }
        graph.assignVertexWeights(vertexWeightAssigner);
        return new Result(graph, stats);
    }
}
