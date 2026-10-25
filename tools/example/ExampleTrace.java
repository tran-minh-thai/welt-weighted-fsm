import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.Metrics;
import welt.core.MniSupportCounter;
import welt.core.Pattern;
import welt.core.PivotVoter;

import java.lang.reflect.Method;
import java.nio.file.Path;

/**
 * Traces the weight check of the path A-B-C on the example graph with the WeLT settings of the
 * benchmark (voter 1,1,1, weight-aware order on): the start pattern vertex, the matching order,
 * the adjacency of the start image, and the answer and backtracking nodes with and without the
 * weight branch-and-bound. The private start and order methods are called by reflection, so the
 * trace runs the code of the miner and not a copy of it.
 *
 *   java -cp <classes> ExampleTrace <graph.lg> <minWeight> <labelA> <labelB> <labelC>
 */
public final class ExampleTrace {

    public static void main(String[] args) throws Exception {
        LabeledWeightedGraph g = new LgGraphReader().read(Path.of(args[0])).graph;
        double minWeight = Double.parseDouble(args[1]);
        int la = Integer.parseInt(args[2]), lb = Integer.parseInt(args[3]), lc = Integer.parseInt(args[4]);
        int el = g.neighbors(0).get(0).edgeLabel;
        Pattern p = Pattern.singleEdge(la, lb, el).withLeaf(1, lc, el); // vertices 0:A 1:B 2:C

        MniSupportCounter counter = new MniSupportCounter(g, new Metrics());
        counter.setVoter(PivotVoter.WELT_DEFAULT);
        counter.setWeightAwareOrdering(true);
        Method choose = MniSupportCounter.class.getDeclaredMethod("chooseStart", Pattern.class);
        choose.setAccessible(true);
        int start = (int) choose.invoke(counter, p);
        Method orderM = MniSupportCounter.class.getDeclaredMethod("weightAwareOrder", Pattern.class, int.class);
        orderM.setAccessible(true);
        int[] order = (int[]) orderM.invoke(counter, p, start);
        StringBuilder ol = new StringBuilder();
        for (int v : order) ol.append(ol.length() == 0 ? "" : ",").append(p.nodeLabel(v));
        System.out.println("start_label=" + p.nodeLabel(start));
        System.out.println("order_labels=" + ol);

        int first = -1;
        for (int v = 0; v < g.numVertices() && first < 0; v++) {
            if (g.vertexLabel(v) == p.nodeLabel(start) && g.neighbors(v).size() >= p.degree(start)) first = v;
        }
        StringBuilder adj = new StringBuilder();
        for (LabeledWeightedGraph.Adjacency a : g.neighbors(first)) {
            adj.append(adj.length() == 0 ? "" : ",").append(a.to).append(':').append(a.edgeLabel).append(':')
               .append(g.vertexLabel(a.to)).append(':').append(a.weight);
        }
        System.out.println("first_start_image=" + first);
        System.out.println("first_start_adjacency=" + adj); // to:edgeLabel:vertexLabel:weight, in scan order

        for (boolean bnb : new boolean[]{true, false}) {
            Metrics m = new Metrics();
            MniSupportCounter c = new MniSupportCounter(g, m);
            c.setVoter(PivotVoter.WELT_DEFAULT);
            c.setWeightAwareOrdering(true);
            c.setWeightBranchAndBound(bnb);
            boolean ok = c.embedsWithMinWeight(p, minWeight);
            System.out.println("embeds_" + (bnb ? "bnb" : "nobnb") + "=" + ok + " backtrack=" + m.backtrackWeight);
        }
    }
}
