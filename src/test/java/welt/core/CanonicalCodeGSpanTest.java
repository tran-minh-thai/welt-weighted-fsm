package welt.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Validation of the MINIMUM DFS CODE (gSpan) in place of the {@code n!} brute-force
 * enumeration:
 * <ol>
 *   <li>ISOMORPHISM INVARIANCE: every vertex permutation of a pattern yields the same code.</li>
 *   <li>IDENTICAL PARTITION to the brute-force version: for every pair of patterns (n≤9),
 *       {@code minDfs(A)=minDfs(B) ⇔ bruteForce(A)=bruteForce(B)}.</li>
 *   <li>LIFTING THE n≤9 CAP: a 12-vertex pattern produces a code (without throwing) and
 *       remains permutation-invariant.</li>
 * </ol>
 */
class CanonicalCodeGSpanTest {

    /** Build an isomorphic pattern by renaming vertices according to {@code perm} (perm[old]=new). */
    private static Pattern permute(Pattern p, int[] perm) {
        int n = p.nodeCount();
        int[] labels = new int[n];
        for (int old = 0; old < n; old++) labels[perm[old]] = p.nodeLabel(old);
        List<int[]> es = new ArrayList<>();
        for (int[] e : p.edges()) es.add(new int[]{perm[e[0]], perm[e[1]], e[2]});
        return Pattern.of(labels, es.toArray(new int[0][]));
    }

    private static void allPermutations(int[] arr, int k, List<int[]> out) {
        if (k == arr.length) { out.add(arr.clone()); return; }
        for (int i = k; i < arr.length; i++) {
            int t = arr[k]; arr[k] = arr[i]; arr[i] = t;
            allPermutations(arr, k + 1, out);
            t = arr[k]; arr[k] = arr[i]; arr[i] = t;
        }
    }

    // ---- a few representative patterns ----
    private static Pattern path3() { // a-b-c, labels 1-2-3
        return Pattern.of(new int[]{1, 2, 3}, new int[][]{{0, 1, 0}, {1, 2, 0}});
    }
    private static Pattern triangle() { // labels 1-1-1
        return Pattern.of(new int[]{1, 1, 1}, new int[][]{{0, 1, 0}, {1, 2, 0}, {0, 2, 0}});
    }
    private static Pattern star4() { // center with label 9, three leaves with label 1
        return Pattern.of(new int[]{9, 1, 1, 1}, new int[][]{{0, 1, 0}, {0, 2, 0}, {0, 3, 0}});
    }
    private static Pattern squareChord() { // 4-vertex cycle + 1 chord
        return Pattern.of(new int[]{1, 2, 1, 2},
                new int[][]{{0, 1, 0}, {1, 2, 0}, {2, 3, 0}, {3, 0, 0}, {0, 2, 5}});
    }
    private static Pattern labeledEdges() { // same structure but different edge labels
        return Pattern.of(new int[]{1, 1, 1}, new int[][]{{0, 1, 7}, {1, 2, 3}});
    }

    private static Pattern pathN(int n) {
        int[] labels = new int[n];
        for (int i = 0; i < n; i++) labels[i] = 1 + (i % 3);
        List<int[]> es = new ArrayList<>();
        for (int i = 0; i + 1 < n; i++) es.add(new int[]{i, i + 1, 0});
        return Pattern.of(labels, es.toArray(new int[0][]));
    }

    @Test
    void isomorphicUnderAllPermutations() {
        for (Pattern p : List.of(path3(), triangle(), star4(), squareChord(), labeledEdges())) {
            int n = p.nodeCount();
            int[] id = new int[n];
            for (int i = 0; i < n; i++) id[i] = i;
            List<int[]> perms = new ArrayList<>();
            allPermutations(id, 0, perms);
            String ref = CanonicalCode.of(p);
            for (int[] perm : perms) {
                assertEquals(ref, CanonicalCode.of(permute(p, perm)),
                        "the DFS code must be invariant under every vertex permutation");
            }
        }
    }

    @Test
    void minDfsPartitionMatchesBruteForce() {
        List<Pattern> samples = new ArrayList<>();
        // include permuted variants as well, to obtain many isomorphic/non-isomorphic pairs
        for (Pattern base : List.of(path3(), triangle(), star4(), squareChord(), labeledEdges())) {
            int n = base.nodeCount();
            int[] id = new int[n];
            for (int i = 0; i < n; i++) id[i] = i;
            List<int[]> perms = new ArrayList<>();
            allPermutations(id, 0, perms);
            for (int pi = 0; pi < perms.size(); pi += Math.max(1, perms.size() / 4)) {
                samples.add(permute(base, perms.get(pi)));
            }
        }
        for (int i = 0; i < samples.size(); i++) {
            for (int j = i; j < samples.size(); j++) {
                boolean dfsEq = CanonicalCode.of(samples.get(i)).equals(CanonicalCode.of(samples.get(j)));
                boolean bruteEq = CanonicalCode.bruteForceOf(samples.get(i))
                        .equals(CanonicalCode.bruteForceOf(samples.get(j)));
                assertEquals(bruteEq, dfsEq,
                        "DFS and brute force must partition the isomorphism classes identically (pair " + i + "," + j + ")");
            }
        }
    }

    @Test
    void distinctStructuresDiffer() {
        String a = CanonicalCode.of(path3());
        String b = CanonicalCode.of(triangle());
        String c = CanonicalCode.of(star4());
        assertNotEquals(a, b);
        assertNotEquals(a, c);
        assertNotEquals(b, c);
        // different edge labels ⇒ different code
        assertNotEquals(CanonicalCode.of(path3()), CanonicalCode.of(labeledEdges()));
    }

    private static Pattern star(int leaves, int leafLabel) {
        int n = leaves + 1;
        int[] labels = new int[n];
        labels[0] = 9;
        for (int i = 1; i < n; i++) labels[i] = leafLabel;
        List<int[]> es = new ArrayList<>();
        for (int i = 1; i < n; i++) es.add(new int[]{0, i, 0});
        return Pattern.of(labels, es.toArray(new int[0][]));
    }

    private static Pattern clique(int k) {
        int[] labels = new int[k];
        java.util.Arrays.fill(labels, 1);
        List<int[]> es = new ArrayList<>();
        for (int i = 0; i < k; i++) for (int j = i + 1; j < k; j++) es.add(new int[]{i, j, 0});
        return Pattern.of(labels, es.toArray(new int[0][]));
    }

    /**
     * HIGHLY SYMMETRIC patterns (stars/cliques with uniform labels) — where the
     * un-refined version blows up by {@code k!}. Checks: (1) isomorphism invariance
     * across many random permutations; (2) WL refinement does NOT corrupt the
     * partition — cross-checked against brute force (n≤9).
     */
    @Test
    void symmetricPatternsCorrectAndMatchBruteForce() {
        java.util.Random rnd = new java.util.Random(42);
        List<Pattern> sym = new ArrayList<>();
        for (int L = 3; L <= 8; L++) sym.add(star(L, 1)); // n up to 9
        for (int K = 3; K <= 5; K++) sym.add(clique(K));
        for (Pattern p : sym) {
            int n = p.nodeCount();
            String dfsRef = CanonicalCode.of(p);
            String bruteRef = CanonicalCode.bruteForceOf(p);
            for (int trial = 0; trial < 40; trial++) {
                int[] perm = randomPerm(n, rnd);
                Pattern q = permute(p, perm);
                assertEquals(dfsRef, CanonicalCode.of(q), "DFS invariant on symmetric patterns");
                // DFS classifies identically to brute force: same isomorphism ⇒ both codes equal the reference
                assertEquals(bruteRef, CanonicalCode.bruteForceOf(q));
            }
        }
        // two DIFFERENT symmetric patterns must have different codes (no false merging)
        assertNotEquals(CanonicalCode.of(star(5, 1)), CanonicalCode.of(clique(5)));
        assertNotEquals(CanonicalCode.of(star(4, 1)), CanonicalCode.of(star(5, 1)));
    }

    private static int[] randomPerm(int n, java.util.Random rnd) {
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) perm[i] = i;
        for (int i = n - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = perm[i]; perm[i] = perm[j]; perm[j] = t;
        }
        return perm;
    }

    @Test
    void liftsNineNodeCap() {
        Pattern big = pathN(12); // exceeds the former n≤9 cap
        String ref = assertDoesNotThrow(() -> CanonicalCode.of(big),
                "a 12-vertex pattern must produce a code (the n≤9 cap has been lifted)");
        // permutation invariance on a large pattern (checked with a few fixed random permutations)
        int[][] someperms = {
                {11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0},
                {0, 2, 4, 6, 8, 10, 1, 3, 5, 7, 9, 11},
                {5, 4, 3, 2, 1, 0, 6, 7, 8, 9, 10, 11},
        };
        for (int[] perm : someperms) {
            assertEquals(ref, CanonicalCode.of(permute(big, perm)),
                    "the code of a large pattern must be permutation-invariant");
        }
    }
}
