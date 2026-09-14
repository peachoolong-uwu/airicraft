package ai.moeru.airicraft.agent.lighting;

import org.junit.jupiter.api.Test;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import ai.moeru.airicraft.agent.tasks.WorldTaskType;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightingPolicyEvaluatorTest {
	@Test
	void freshAndResetRuntimeAutomaticallyLightsDarkUndergroundWork() {
		var runtime = new LightingRuntime();
		assertTrue(LightingPolicyEvaluator.shouldPlace(runtime.policy(), true, true, false, 0, 0, false));
		assertFalse(LightingPolicyEvaluator.shouldPlace(runtime.policy(), true, true, true, 15, 0, false));
		runtime.configure(false, LightingPolicy.Mode.DARKNESS, 0, true, 6);
		assertFalse(runtime.policy().enabled());
		runtime.reset();
		assertTrue(runtime.policy().enabled());
	}

	@Test
	void darknessPolicyRequiresSupportedActivityTorchAndUndergroundWhenConfigured() {
		LightingPolicy policy = new LightingPolicy(true, LightingPolicy.Mode.DARKNESS, 2, true, 6, 1L);

		assertTrue(LightingPolicyEvaluator.shouldPlace(policy, true, true, false, 2, 0, false));
		assertFalse(LightingPolicyEvaluator.shouldPlace(policy, false, true, false, 0, 0, false));
		assertFalse(LightingPolicyEvaluator.shouldPlace(policy, true, false, false, 0, 0, false));
		assertFalse(LightingPolicyEvaluator.shouldPlace(policy, true, true, true, 0, 0, false));
		assertFalse(LightingPolicyEvaluator.shouldPlace(policy, true, true, false, 3, 0, false));
	}

	@Test
	void lightingAccompaniesTravelAndMiningButNotOtherInteractionOwners() {
		for (WorldTaskType activity : WorldTaskType.values()) {
			assertEquals(activity == WorldTaskType.MINE || activity == WorldTaskType.NAVIGATE,
				LightingPolicyEvaluator.supportsActivity(activity), activity.name());
		}
		assertFalse(LightingPolicyEvaluator.supportsActivity(null));
	}

	@Test
	void spawnProofPolicyUsesBlockLightAndHonorsSpacing() {
		LightingPolicy policy = new LightingPolicy(true, LightingPolicy.Mode.SPAWN_PROOF, 7, false, 6, 1L);

		assertTrue(LightingPolicyEvaluator.shouldPlace(policy, true, true, true, 15, 7, false));
		assertFalse(LightingPolicyEvaluator.shouldPlace(policy, true, true, true, 0, 8, false));
		assertFalse(LightingPolicyEvaluator.shouldPlace(policy, true, true, true, 0, 0, true));
	}

	@Test
	void placementCandidatesPreferWallsOnTheLeftThenRightBeforeFloorFallbacks() {
		var candidates = LightingRuntime.placementCandidates(new BlockPos(10, 20, 30), Direction.NORTH);

		assertEquals(10, candidates.size());
		assertEquals(new BlockPos(9, 21, 31), candidates.get(0).support());
		assertEquals(Direction.EAST, candidates.get(0).face());
		assertEquals("left", candidates.get(0).side());
		assertEquals(LightingRuntime.PlacementSurface.WALL, candidates.get(0).surface());
		assertEquals(new BlockPos(11, 21, 31), candidates.get(3).support());
		assertEquals(Direction.WEST, candidates.get(3).face());
		assertEquals("right", candidates.get(3).side());
		assertEquals(LightingRuntime.PlacementSurface.WALL, candidates.get(3).surface());
		assertEquals(new BlockPos(10, 19, 31), candidates.get(6).support());
		assertEquals(Direction.UP, candidates.get(6).face());
		assertEquals("floor", candidates.get(6).side());
		assertEquals(LightingRuntime.PlacementSurface.FLOOR, candidates.get(6).surface());
	}
}
