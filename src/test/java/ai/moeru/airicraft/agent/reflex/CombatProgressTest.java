package ai.moeru.airicraft.agent.reflex;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CombatProgressTest {
    private static Map<String, CombatProgress.Target> skeleton(double distance, float health) {
        return Map.of("skeleton", new CombatProgress.Target(distance, health));
    }
    @Test void stationaryShooterStallsAfterTenSecondsDespiteContinuedExposure() {
        CombatProgress state = null;
        // This detector has no incoming-damage reset: repeated hits cannot renew ownership.
        for (int tick = 0; tick <= 200; tick++) {
            state = CombatProgress.observe(state, tick, skeleton(7.5, 20));
            assertEquals(tick == 200, state.stalled());
        }
    }
    @Test void closingAndHealthReductionResetButOscillationAndHealingDoNot() {
        var state = CombatProgress.observe(null, 0, skeleton(8, 20));
        state = CombatProgress.observe(state, 100, skeleton(6.9, 20));
        assertEquals(100, state.lastProgressTick());
        state = CombatProgress.observe(state, 200, skeleton(8, 20));
        state = CombatProgress.observe(state, 300, skeleton(6.9, 20));
        assertEquals(100, state.lastProgressTick());
        state = CombatProgress.observe(state, 400, skeleton(6.9, 18));
        assertEquals(400, state.lastProgressTick());
        state = CombatProgress.observe(state, 600, skeleton(6.9, 20));
        state = CombatProgress.observe(state, 800, skeleton(6.9, 18));
        assertTrue(state.stalled(), "Repeating a damage/heal cycle without a new health low is not progress");
        assertFalse(CombatProgress.observe(state, 801, skeleton(6.9, 16)).stalled());
    }
    @Test void targetChurnDoesNotResetTimerAndNoThreatEndsObservation() {
        var state = CombatProgress.observe(null, 0, skeleton(8, 20));
        state = CombatProgress.observe(state, 400, Map.of("new", new CombatProgress.Target(4, 20)));
        assertTrue(state.stalled());
        assertNull(CombatProgress.observe(state, 401, Map.of()));
    }
    @Test void fractionalDamageAccumulatesToMeaningfulProgress() {
        var state = CombatProgress.observe(null, 0, skeleton(8, 20));
        state = CombatProgress.observe(state, 100, skeleton(8, 19.5F));
        assertEquals(0, state.lastProgressTick());
        state = CombatProgress.observe(state, 200, skeleton(8, 19));
        assertEquals(200, state.lastProgressTick());
    }
    @Test void confirmedDeathCountsButMerelyLosingSightDoesNot() {
        var state = CombatProgress.observe(null, 0, skeleton(8, 20));
        assertTrue(CombatProgress.observe(state, 400, skeleton(8, 20), false).stalled());
        assertFalse(CombatProgress.observe(state, 400, skeleton(8, 20), true).stalled());
    }
}
