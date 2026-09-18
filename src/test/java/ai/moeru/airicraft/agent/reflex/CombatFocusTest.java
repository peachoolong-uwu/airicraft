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
	private static CombatFocus.Candidate mob(String type, double distance, boolean baby) {
		return new CombatFocus.Candidate(type, distance, true, .1,
			type.equals("witch") || type.equals("skeleton"), false, "minecraft:" + type, baby);
	}

	@Test void witchOutranksEvenContactingBabyZombie() {
		assertEquals("witch", CombatFocus.select(List.of(mob("witch", 8, false), mob("zombie", 1.5, true))));
	}

	@Test void babyZombieOutranksSpiderAndUnpressuredSkeleton() {
		assertEquals("zombie", CombatFocus.select(List.of(mob("zombie", 6, true), mob("spider", 6, false), mob("skeleton", 5, false))));
	}

	@Test void spiderOutranksAdultZombie() {
		assertEquals("spider", CombatFocus.select(List.of(mob("zombie", 2, false), mob("spider", 4, false))));
	}

	@Test void skeletonPriorityDependsOnMeleePressure() {
		assertEquals("skeleton", CombatFocus.select(List.of(mob("zombie", 6, false), mob("spider", 6, false), mob("skeleton", 5, false))));
		assertEquals("zombie", CombatFocus.select(List.of(mob("zombie", 3, false), mob("skeleton", 5, false))));
		assertEquals("spider", CombatFocus.select(List.of(mob("spider", 3, false), mob("skeleton", 5, false))));
		var rushing = new CombatFocus.Candidate("rush", 5, true, .4, false, false, "minecraft:zombie", false);
		assertEquals("rush", CombatFocus.select(List.of(rushing, mob("skeleton", 5, false))));
	}

	@Test void hiddenWitchDoesNotDisplaceVisibleZombie() {
		var witch = new CombatFocus.Candidate("witch", 3, false, 0, true, false, "minecraft:witch", false);
		assertEquals("zombie", CombatFocus.select(List.of(witch, mob("zombie", 3, false))));
	}
}
