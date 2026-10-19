package welt.runner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The run mode label and the exact-mode guard of {@link BenchmarkMain}. */
class BenchmarkModeTest {

    @Test
    void modeFollowsBudget() {
        assertEquals("exact", BenchmarkMain.mode(0));
        assertEquals("approx", BenchmarkMain.mode(800));
    }

    @Test
    void guardFiresOnlyForAbortsInExactMode() {
        assertNull(BenchmarkMain.exactModeViolation(0, 0));
        assertNotNull(BenchmarkMain.exactModeViolation(0, 1), "an abort in exact mode must be reported");
        assertNull(BenchmarkMain.exactModeViolation(800, 5), "aborts are expected in approximate mode");
    }
}
