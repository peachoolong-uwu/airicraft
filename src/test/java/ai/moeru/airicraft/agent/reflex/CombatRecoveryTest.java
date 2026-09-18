package ai.moeru.airicraft.agent.reflex;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatRecoveryTest {
    @Test void waterPreemptsLandCombatEvenWithAirAndFootSupport() {
        assertEquals(CombatRecovery.REACH_DRY_GROUND, CombatRecovery.READY.next(true, true, false));
    }
    @Test void surfaceBreathingOrJumpingOutDoesNotPrematurelyResumeCombat() {
        assertEquals(CombatRecovery.REACH_DRY_GROUND, CombatRecovery.REACH_DRY_GROUND.next(true, false, false));
        assertEquals(CombatRecovery.REACH_DRY_GROUND, CombatRecovery.REACH_DRY_GROUND.next(false, false, true));
        assertEquals(CombatRecovery.REACH_DRY_GROUND, CombatRecovery.REACH_DRY_GROUND.next(false, true, false));
    }
    @Test void drySupportedLandingResumesCombatAndLaterWaterCanRetrigger() {
        var ready = CombatRecovery.REACH_DRY_GROUND.next(false, true, true);
        assertEquals(CombatRecovery.READY, ready);
        assertEquals(CombatRecovery.REACH_DRY_GROUND, ready.next(true, false, false));
    }
    @Test void ordinaryJumpDoesNotStartWaterRecovery() {
        assertEquals(CombatRecovery.READY, CombatRecovery.READY.next(false, false, false));
    }
}
