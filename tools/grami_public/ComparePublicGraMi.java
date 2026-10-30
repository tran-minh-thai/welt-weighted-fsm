import welt.core.CanonicalCode;
import welt.core.LabeledWeightedGraph;
import welt.core.LgGraphReader;
import welt.core.Metrics;
import welt.core.MiningEngine;
import welt.core.Pattern;
import welt.strategy.GraMiStrategy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares the frequent subgraphs written by the public GraMi (its Output.txt: time, count, then one
 * "i:" block of "v id label" and "e a b label" lines per pattern) with those of GraMiStrategy on the
 * same graph and support, by canonical code.
 *
 * <pre>java -cp target/classes:&lt;dir&gt; ComparePublicGraMi &lt;dataset.lg&gt; &lt;minSup&gt; &lt;Output.txt&gt;</pre>
 *
 * Prints the count each side reports and the number of codes in both, in the public output only and in
 * GraMiStrategy only. Exit code 0 when the two sets are equal, 1 when they differ, 2 when the output
 * file holds no pattern block although its count line says it should, or its count line disagrees with
 * its blocks.
 */
public final class ComparePublicGraMi {

    private ComparePublicGraMi() {
    }

    public static void main(String[] args) throws Exception {
        LabeledWeightedGraph g = new LgGraphReader().read(Path.of(args[0])).graph;
        int minSup = Integer.parseInt(args[1]);
        List<String> lines = Files.readAllLines(Path.of(args[2]));
        int declared = Integer.parseInt(lines.get(1).trim());
        Set<String> pub = parse(lines);
        Set<String> ours = new TreeSet<>();
        for (MiningEngine.FrequentPattern fp : new MiningEngine(g, new Metrics()).mine(minSup, new GraMiStrategy()))
            ours.add(CanonicalCode.of(fp.pattern));
        Set<String> both = new TreeSet<>(pub);
        both.retainAll(ours);
        System.out.printf("public_count=%d public_blocks=%d ours=%d both=%d public_only=%d ours_only=%d%n",
                declared, pub.size(), ours.size(), both.size(), pub.size() - both.size(), ours.size() - both.size());
        if (pub.size() != declared) {
            System.out.println("ERROR: the count line of the output does not match its pattern blocks");
            System.exit(2);
        }
        System.exit(pub.equals(ours) ? 0 : 1);
    }

    static Set<String> parse(List<String> lines) {
        Set<String> codes = new TreeSet<>();
        Map<Integer, Integer> labels = null;
        List<int[]> edges = null;
        for (String raw : lines.subList(Math.min(2, lines.size()), lines.size())) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.endsWith(":")) {
                if (labels != null) codes.add(code(labels, edges));
                labels = new HashMap<>();
                edges = new ArrayList<>();
            } else if (labels != null && line.startsWith("v ")) {
                String[] p = line.split("\\s+");
                labels.put(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            } else if (labels != null && line.startsWith("e ")) {
                String[] p = line.split("\\s+");
                edges.add(new int[]{Integer.parseInt(p[1]), Integer.parseInt(p[2]), (int) Double.parseDouble(p[3])});
            }
        }
        if (labels != null) codes.add(code(labels, edges));
        return codes;
    }

    private static String code(Map<Integer, Integer> labels, List<int[]> edges) {
        int[] l = new int[labels.size()];
        for (Map.Entry<Integer, Integer> e : labels.entrySet()) l[e.getKey()] = e.getValue();
        return CanonicalCode.of(Pattern.of(l, edges.toArray(new int[0][])));
    }
}
