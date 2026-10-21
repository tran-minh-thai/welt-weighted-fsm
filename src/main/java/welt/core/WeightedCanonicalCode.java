package welt.core;

/**
 * WEIGHTED canonical code: the key for pattern deduplication while the
 * {@link welt.strategy.WeightedLookupTable} is built (its entries are then read by a path key).
 *
 * <p><b>Modeling note.</b> In WeLT's bottleneck model, weight is a property of an
 * OCCURRENCE in G (W(S) = max over occurrences of the minimum edge weight), NOT an
 * intrinsic property of the pattern — a pattern carries only vertex labels and
 * structural edge labels. Hence the weighted canonical code here COINCIDES with the
 * structural canonical code {@link CanonicalCode}: two structurally isomorphic
 * patterns share the same key, and the "weight" aspect is captured by the value
 * {@code MaxW(p)} stored in the lookup table (rather than in the key).
 *
 * <p>This thin wrapper isolates the "lookup-table key / deduplication" role to match
 * the paper's terminology and to allow future extension (if patterns later carry an
 * intrinsic weight class, only this class needs changing, leaving the rest intact).
 */
public final class WeightedCanonicalCode {

    private WeightedCanonicalCode() {
    }

    /** Canonical key of a pattern (currently = structural code, see class note). */
    public static String of(Pattern p) {
        return CanonicalCode.of(p);
    }
}
