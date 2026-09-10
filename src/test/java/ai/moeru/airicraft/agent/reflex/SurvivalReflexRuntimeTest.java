package ai.moeru.airicraft.agent.reflex;

import ai.moeru.airicraft.agent.tasks.UnderwaterEscapeSearch;
import ai.moeru.airicraft.agent.tasks.UnderwaterEscapeNavigator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurvivalReflexRuntimeTest {
	@Test
	void drowningStartsAtLowAirThresholdOrFromDrowningDamage() {
		assertTrue(SurvivalReflexRuntime.shouldStartDrowning(true, 100, 100, false));
		assertFalse(SurvivalReflexRuntime.shouldStartDrowning(true, 101, 100, false));
		assertFalse(SurvivalReflexRuntime.shouldStartDrowning(false, 0, 100, false));
		assertTrue(SurvivalReflexRuntime.shouldStartDrowning(false, 300, 100, true));
	}

	@Test
	void drowningRequiresAirMarginAndTwelveStableTicksToResolve() {
		assertFalse(SurvivalReflexRuntime.airRecoveryMarginReached(false, 279, 300));
		assertTrue(SurvivalReflexRuntime.airRecoveryMarginReached(false, 280, 300));
		assertTrue(SurvivalReflexRuntime.airRecoveryMarginReached(false, 300, 300));
		assertFalse(SurvivalReflexRuntime.airRecoveryMarginReached(true, 300, 300));
		assertFalse(SurvivalReflexRuntime.drowningResolved(11));
		assertTrue(SurvivalReflexRuntime.drowningResolved(12));
	}

	@Test
	void stableBreathingEndsActiveDrowningBeforeSafeLandWork() {
		assertEquals(SurvivalReflexAction.REACH_SAFE_LAND, SurvivalReflexRuntime.drowningAction(false));
		assertEquals(SurvivalReflexAction.SWIM_TO_AIR, SurvivalReflexRuntime.drowningAction(true));
		assertTrue(SurvivalReflexRuntime.stableDrowningRecovery(true));
		assertFalse(SurvivalReflexRuntime.stableDrowningRecovery(false));
		assertTrue(SurvivalReflexRuntime.shouldKeepDrowningSafetyHold(false, false));
		assertFalse(SurvivalReflexRuntime.shouldKeepDrowningSafetyHold(false, true));
		assertTrue(SurvivalReflexRuntime.shouldKeepDrowningSafetyHold(true, true));
		assertEquals(UnderwaterEscapeSearch.SearchMode.BREATHABLE,
			SurvivalReflexRuntime.drowningSearchMode(false, true, 80, 300));
		assertEquals(UnderwaterEscapeSearch.SearchMode.BREATHABLE,
			SurvivalReflexRuntime.drowningSearchMode(true, false, 300, 300));
		assertEquals(UnderwaterEscapeSearch.SearchMode.SAFE_STANDING,
			SurvivalReflexRuntime.drowningSearchMode(false, false, 280, 300));
	}

	@Test
	void terminalSafeLandSearchFallsBackToAfloatHold() {
		assertFalse(SurvivalReflexRuntime.safeLandSearchExhausted(
			UnderwaterEscapeSearch.SearchStatus.SEARCHING,
			UnderwaterEscapeNavigator.Phase.EXHAUSTED
		));
		assertFalse(SurvivalReflexRuntime.safeLandSearchExhausted(
			UnderwaterEscapeSearch.SearchStatus.CELL_BUDGET_EXHAUSTED,
			UnderwaterEscapeNavigator.Phase.WAYPOINT_FALLBACK
		));
		assertTrue(SurvivalReflexRuntime.safeLandSearchExhausted(
			UnderwaterEscapeSearch.SearchStatus.CELL_BUDGET_EXHAUSTED,
			UnderwaterEscapeNavigator.Phase.EXHAUSTED
		));
		assertTrue(SurvivalReflexRuntime.safeLandSearchExhausted(
			UnderwaterEscapeSearch.SearchStatus.COMPLETE,
			UnderwaterEscapeNavigator.Phase.EXHAUSTED
		));
	}

	@Test
	void threatAdmissionRequiresAggroRatherThanProximity() {
		assertFalse(SurvivalReflexRuntime.shouldDetectProactiveThreat(false, true));
		assertTrue(SurvivalReflexRuntime.shouldDetectProactiveThreat(true, true));
		assertFalse(SurvivalReflexRuntime.shouldDetectProactiveThreat(true, false));
	}

	@Test
	void threatResolutionHonorsDamageCooldown() {
		assertFalse(SurvivalReflexRuntime.mobThreatsResolved(1, 200, 100, 60));
		assertFalse(SurvivalReflexRuntime.mobThreatsResolved(0, 159, 100, 60));
		assertTrue(SurvivalReflexRuntime.mobThreatsResolved(0, 160, 100, 60));
	}

	@Test
	void closeQuarterAttackIgnoresMovementModeButRequiresRangeSightAndCooldown() {
		assertTrue(SurvivalReflexRuntime.shouldAttackCloseThreat(3.0D, true, 0.92F));
		assertFalse(SurvivalReflexRuntime.shouldAttackCloseThreat(3.01D, true, 1.0F));
		assertFalse(SurvivalReflexRuntime.shouldAttackCloseThreat(2.0D, false, 1.0F));
		assertFalse(SurvivalReflexRuntime.shouldAttackCloseThreat(2.0D, true, 0.91F));
	}

	@Test
	void ranksCombatHotbarItemsAheadOfIncidentalBlocks() {
		assertTrue(SurvivalReflexRuntime.combatHotbarRank("minecraft:stone_pickaxe")
			< SurvivalReflexRuntime.combatHotbarRank("minecraft:leaf_litter"));
		assertTrue(SurvivalReflexRuntime.combatHotbarRank("minecraft:iron_sword")
			< SurvivalReflexRuntime.combatHotbarRank("minecraft:stone_pickaxe"));
		assertEquals(Integer.MAX_VALUE, SurvivalReflexRuntime.combatHotbarRank("minecraft:dirt"));
	}

	@Test
	void onlyBlockedOccludedPursuitCountsAsShelter() {
		for (var route : SurvivalReflexRuntime.RouteStatus.values()) {
			for (boolean visible : new boolean[]{false, true}) {
				assertEquals(route == SurvivalReflexRuntime.RouteStatus.BLOCKED && !visible
					? SurvivalReflexRuntime.SecurityKind.SEALED : SurvivalReflexRuntime.SecurityKind.UNSAFE,
					SurvivalReflexRuntime.classifyThreatSecurity(route, visible));
			}
		}
	}

	@Test
	void newDangerPreemptsSafetyHoldButDoesNotRestartActiveReflex() {
		assertTrue(SurvivalReflexRuntime.shouldBeginReflex(SurvivalReflexState.IDLE, true));
		assertTrue(SurvivalReflexRuntime.shouldBeginReflex(SurvivalReflexState.AWAITING_PLANNER, true));
		assertFalse(SurvivalReflexRuntime.shouldBeginReflex(SurvivalReflexState.ACTIVE, true));
		assertFalse(SurvivalReflexRuntime.shouldBeginReflex(SurvivalReflexState.AWAITING_PLANNER, false));
	}

	@Test
	void resolvedDrowningHoldTreadsWaterUntilPlannerDecision() {
		assertTrue(SurvivalReflexRuntime.shouldMaintainDrowningSafetyHold(
			SurvivalReflexState.AWAITING_PLANNER, SurvivalReflexCause.DROWNING, true));
		assertFalse(SurvivalReflexRuntime.shouldMaintainDrowningSafetyHold(
			SurvivalReflexState.AWAITING_PLANNER, SurvivalReflexCause.DROWNING, false));
		assertFalse(SurvivalReflexRuntime.shouldMaintainDrowningSafetyHold(
			SurvivalReflexState.ACTIVE, SurvivalReflexCause.DROWNING, true));
		assertFalse(SurvivalReflexRuntime.shouldMaintainDrowningSafetyHold(
			SurvivalReflexState.AWAITING_PLANNER, SurvivalReflexCause.MOB_ATTACK, true));
	}

	@Test
	void resumeRequiresResolvedMatchingSafetyHold() {
		SurvivalReflexSnapshot active = snapshot(SurvivalReflexState.ACTIVE, "hold-1");
		SurvivalReflexSnapshot awaiting = snapshot(SurvivalReflexState.AWAITING_PLANNER, "hold-1");

		assertEquals(SurvivalReflexRuntime.ResumeResult.REFLEX_ACTIVE,
			SurvivalReflexRuntime.validateResume(active, "hold-1"));
		assertEquals(SurvivalReflexRuntime.ResumeResult.NO_SAFETY_HOLD,
			SurvivalReflexRuntime.validateResume(SurvivalReflexSnapshot.idle(), "hold-1"));
		assertEquals(SurvivalReflexRuntime.ResumeResult.STALE_SAFETY_HOLD,
			SurvivalReflexRuntime.validateResume(awaiting, "old-hold"));
		assertEquals(SurvivalReflexRuntime.ResumeResult.RESUMED,
			SurvivalReflexRuntime.validateResume(awaiting, "hold-1"));
	}

	@Test
	void activeAndDrowningHoldOwnActuationWhileBothHoldNormalWork() {
		SurvivalReflexSnapshot active = snapshot(SurvivalReflexState.ACTIVE, "hold-1");
		SurvivalReflexSnapshot awaiting = snapshot(SurvivalReflexState.AWAITING_PLANNER, "hold-1");

		assertTrue(active.ownsActuation());
		assertTrue(active.holdsNormalTasks());
		assertTrue(awaiting.ownsActuation());
		assertTrue(awaiting.holdsNormalTasks());
	}

	@Test
	void awaitingPlannerCannotExistWithoutAResumableHoldIdentity() {
		assertThrows(IllegalArgumentException.class, () -> snapshot(
			SurvivalReflexState.AWAITING_PLANNER,
			null
		));
		assertNotNull(SurvivalReflexRuntime.safetyHoldId(null, true));
		assertEquals("existing-hold", SurvivalReflexRuntime.safetyHoldId("existing-hold", true));
		assertNull(SurvivalReflexRuntime.safetyHoldId(null, false));
	}

	private static SurvivalReflexSnapshot snapshot(SurvivalReflexState state, String holdId) {
		return new SurvivalReflexSnapshot(
			state, SurvivalReflexCause.DROWNING, SurvivalReflexAction.SWIM_TO_AIR, 2L, holdId,
			"job-1", "action-1", List.of(), 10.0F, 20.0F, 100, 300, 10L, 20L, 0, null
		);
	}
}
