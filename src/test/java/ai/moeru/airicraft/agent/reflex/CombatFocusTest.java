package ai.moeru.airicraft.agent.reflex;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatFocusTest {
	private static CombatFocus.Candidate melee(String id, double distance, double speed) {
		return new CombatFocus.Candidate(id, distance, true, speed, false, false);
	}

	@Test void closerThreatImmediatelyReplacesPreviousOpponent() {
		assertEquals("adult", CombatFocus.select(List.of(melee("adult", 2.8, .1), melee("baby", 4, .3))));
		assertEquals("baby", CombatFocus.select(List.of(melee("adult", 2.8, .1), melee("baby", 2, .3))));
		assertEquals("adult", CombatFocus.select(List.of(melee("adult", 2.8, .1))));
		assertNull(CombatFocus.select(List.of()));
	}

	@Test void fastApproachingMobOutranksEquallyDistantSlowMob() {
		assertEquals("baby", CombatFocus.select(List.of(melee("adult", 2.8, .1), melee("baby", 2.8, .3))));
	}

	@Test void preparingRangedAttackGainsPriorityUntilMeleeContactIsImminent() {
		var shooter = new CombatFocus.Candidate("shooter", 5, true, 0, true, true);
		assertEquals("shooter", CombatFocus.select(List.of(melee("adult", 4, .1), shooter)));
		assertEquals("baby", CombatFocus.select(List.of(melee("baby", 1.5, .3), shooter)));
	}

	@Test void occludedThreatCannotDisplaceVisibleOpponent() {
		assertEquals("visible", CombatFocus.select(List.of(new CombatFocus.Candidate("hidden", 1, false, .3, false, true), melee("visible", 3, .1))));
	}
}
