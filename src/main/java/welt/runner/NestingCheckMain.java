package welt.runner;

import welt.core.CanonicalCode;
import welt.core.Pattern;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Nesting of the result sets of WEGM, the exact ALL score and WeLT at the same (minSup, tau_w), read
 * from pattern dumps (DUMP_PATTERNS).
 *
 * <p>WEGM counts only occurrences with the same edge weights, the ALL score the occurrences whose
 * edges all weigh at least tau_w, WeLT every occurrence (with one occurrence of bottleneck weight at
 * least tau_w). For every pattern the three supports are therefore ordered, and the skeleton (edge
 * labels set to 0) of every WEGM pattern is an ALL pattern, and every ALL pattern a WeLT pattern.
 *
 * <p>Usage: {@code NestingCheckMain <wegm.tsv> <all.tsv> [<welt.tsv>]}. Prints the counts; exit 1 when
 * a skeleton is missing from ALL or an ALL pattern from WeLT, 2 when nothing could be compared.
 */
public final class NestingCheckMain {

    private NestingCheckMain() {
    }

    /** The pattern of a canonical code ("i j li le lj;" per edge); edge labels set to 0 when asked. */
    static Pattern fromCode(String code, boolean dropEdgeLabels) {
        Map<Integer, Integer> labels = new TreeMap<>();
        List<int[]> edges = new ArrayList<>();
        for (String t : code.trim().split(";")) {
            String[] f = t.trim().split("\\s+");
            if (f.length != 5) continue;
            int i = Integer.parseInt(f[0]), j = Integer.parseInt(f[1]);
            labels.put(i, Integer.parseInt(f[2]));
            labels.put(j, Integer.parseInt(f[4]));
            edges.add(new int[]{i, j, dropEdgeLabels ? 0 : Integer.parseInt(f[3])});
        }
        int[] l = new int[labels.size()];
        for (Map.Entry<Integer, Integer> e : labels.entrySet()) l[e.getKey()] = e.getValue();
        return Pattern.of(l, edges.toArray(new int[0][]));
    }

    /** Canonical codes of a dump, recomputed (edge labels dropped when asked); empty when the file is missing. */
    static Set<String> codes(Path dump, boolean dropEdgeLabels) throws IOException {
        Set<String> s = new LinkedHashSet<>();
        if (dump == null || !Files.exists(dump)) return s;
        for (String line : Files.readAllLines(dump)) {
            if (line.startsWith("code\t") || line.isBlank()) continue;
            s.add(CanonicalCode.of(fromCode(line.split("\t")[0], dropEdgeLabels)));
        }
        return s;
    }

    /** {skeletons, ALL patterns, WeLT patterns, skeletons not in ALL, ALL patterns not in WeLT}; WeLT -1 when absent. */
    static long[] check(Path wegm, Path all, Path welt) throws IOException {
        Set<String> skel = codes(wegm, true), a = codes(all, false);
        boolean hasWelt = welt != null && Files.exists(welt);
        Set<String> w = codes(welt, false);
        long missAll = skel.stream().filter(c -> !a.contains(c)).count();
        long missWelt = hasWelt ? a.stream().filter(c -> !w.contains(c)).count() : -1;
        return new long[]{skel.size(), a.size(), hasWelt ? w.size() : -1, missAll, missWelt};
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: NestingCheckMain <wegm.tsv> <all.tsv> [<welt.tsv>]");
            System.exit(2);
        }
        Path wegm = Path.of(args[0]), all = Path.of(args[1]), welt = args.length > 2 ? Path.of(args[2]) : null;
        for (Path p : new Path[]{wegm, all}) {
            if (!Files.exists(p)) {
                System.out.println("nesting: missing dump " + p);
                System.exit(2);
            }
        }
        long[] r = check(wegm, all, welt);
        System.out.printf("nesting: WEGM skeletons %d, ALL %d, WeLT %s; skeletons not in ALL %d, ALL not in WeLT %s%n",
                r[0], r[1], r[2] < 0 ? "not compared" : r[2], r[3], r[4] < 0 ? "not compared" : r[4]);
        System.out.printf("NESTING_CSV,%d,%d,%d,%d,%d%n", r[0], r[1], r[2], r[3], r[4]);
        if (r[3] > 0 || r[4] > 0) System.exit(1);
        if (r[0] == 0 && (r[2] < 0 || r[1] == 0)) System.exit(2); // nothing could be compared
    }
}
