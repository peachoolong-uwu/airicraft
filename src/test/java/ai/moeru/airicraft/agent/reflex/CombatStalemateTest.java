package ai.moeru.airicraft.agent.reflex;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CombatStalemateTest {
	private static final Vec3d PLAYER = new Vec3d(-34.511, 124, -38.573);
	private static final Map<String, Vec3d> PILLAGER = Map.of("pillager", new Vec3d(-21.740, 123, -41.500));

	@Test void recordedStationaryPillagerEncounterYieldsOnceAfterTwentySeconds() {
		CombatStalemate state = null;
		for (int tick = 0; tick < 400; tick++) {
			state = CombatStalemate.observe(state, tick, PLAYER, PILLAGER, false);
			assertFalse(state.deferred());
		}
		state = CombatStalemate.observe(state, 400, PLAYER, PILLAGER, false);
		assertTrue(state.deferred());
		assertSame(state, CombatStalemate.observe(state, 1400, PLAYER, PILLAGER, false));
		assertSame(state, CombatStalemate.observe(state, 1401, PLAYER.add(-3, 0, 0), PILLAGER, false),
			"Allow the planner's movement without restarting an unchanged distant encounter");
	}

	@Test void meaningfulProgressRestartsObservationButTinyJitterDoesNot() {
		var start = CombatStalemate.observe(null, 0, PLAYER, PILLAGER, false);
		assertEquals(0, CombatStalemate.observe(start, 300, PLAYER.add(0.05, 0, 0), PILLAGER, false).sinceTick());
		var advanced = CombatStalemate.observe(start, 300, PLAYER.add(1, 0, 0), PILLAGER, false);
		assertEquals(300, advanced.sinceTick());
		assertFalse(CombatStalemate.observe(advanced, 400, PLAYER.add(1, 0, 0), PILLAGER, false).deferred());
	}

	@Test void dangerOrChangedThreatEndsDeferral() {
		var deferred = new CombatStalemate(CombatStalemate.Phase.DEFERRED, 0, PLAYER, PILLAGER);
		assertNull(CombatStalemate.observe(deferred, 500, PLAYER, PILLAGER, true));
		assertNull(CombatStalemate.observe(deferred, 500, PLAYER, Map.of(), false));
		assertFalse(CombatStalemate.observe(deferred, 500, PLAYER,
			Map.of("pillager", PILLAGER.get("pillager").add(1, 0, 0)), false).deferred());
		assertFalse(CombatStalemate.observe(deferred, 500, PLAYER,
			Map.of("another_pillager", PILLAGER.get("pillager")), false).deferred());
	}
}
