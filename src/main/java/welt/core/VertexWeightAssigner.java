package welt.core;

import java.util.List;

/**
 * VERTEX WEIGHT assignment function — serves the OWGraMi baseline (vertex weights).
 *
 * <p>CiteSeer (and GraMi's .lg files) do NOT carry vertex weights in the file, so we
 * DERIVE a vertex weight from the incident edges. Default: the mean weight of the
 * incident edges (a vertex's "citation strength"), on the same [0,100] scale as edge
 * weights so that comparing against the threshold τ_w is meaningful. This is part of
 * the OWGraMi REIMPLEMENTATION; the original code may use a different vertex-weight
 * source — clearly noted in the report.
 */
@FunctionalInterface
public interface VertexWeightAssigner {

    double weightOf(int v, List<LabeledWeightedGraph.Adjacency> incident);

    /** Mean weight of the incident edges; isolated vertex → 0. */
    VertexWeightAssigner AVERAGE_INCIDENT_EDGE = (v, incident) -> {
        if (incident.isEmpty()) return 0.0;
        double s = 0.0;
        for (LabeledWeightedGraph.Adjacency a : incident) s += a.weight;
        return s / incident.size();
    };

    /** Maximum incident-edge weight; isolated vertex → 0. */
    VertexWeightAssigner MAX_INCIDENT_EDGE = (v, incident) -> {
        double m = 0.0;
        for (LabeledWeightedGraph.Adjacency a : incident) m = Math.max(m, a.weight);
        return m;
    };
}
