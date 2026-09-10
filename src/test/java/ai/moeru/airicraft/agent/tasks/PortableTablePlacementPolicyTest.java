package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortableTablePlacementPolicyTest {
	@Test
	void preservedDoorwayIsExcludedEvenWhenItIsTheClosestFeasibleSite() {
		GoalPosition origin = position(259, 63, 481);
		GoalPosition doorway = position(259, 63, 480);
		GoalPosition outsidePreservedArea = position(259, 65, 479);
		assertEquals(List.of(outsidePreservedArea), PortableTablePlacementPolicy.rankFeasibleSites(origin,
			List.of(new PortableTablePlacementPolicy.SiteObservation(doorway, true, true, true, false, true),
				observation(outsidePreservedArea, true, false)), 8));
	}

	@Test
	void candidatePositionsIncludePlayerCellForTunnelFallback() {
		GoalPosition origin = position(2, 59, -5);

		assertTrue(PortableTablePlacementPolicy.candidatePositions(origin).contains(origin));
		assertTrue(PortableTablePlacementPolicy.candidatePositions(origin).contains(position(3, 61, -5)));
	}

	@Test
	void carriedTableWinsOverAWorkstationSeveralBlocksBelowThePlayer() {
		assertEquals(
			CraftingTaskExecutor.WorkbenchSetupAction.PLACE_PORTABLE_TABLE,
			CraftingTaskExecutor.initialWorkbenchSetupAction(true, true, true)
		);
		assertEquals(
			CraftingTaskExecutor.WorkbenchSetupAction.REUSE_NEARBY_TABLE,
			CraftingTaskExecutor.initialWorkbenchSetupAction(true, false, true)
		);
	}

	@Test
	void adjacentSiteIsPreferredButPlayerSpaceSurvivesAttemptLimit() {
		GoalPosition origin = position(0, 64, 0);
		List<PortableTablePlacementPolicy.SiteObservation> observations = new ArrayList<>();
		for (GoalPosition candidate : PortableTablePlacementPolicy.candidatePositions(origin)) {
			observations.add(observation(candidate, true, candidate.equals(origin)));
		}

		List<GoalPosition> ranked = PortableTablePlacementPolicy.rankFeasibleSites(origin, observations, 3);

		assertEquals(3, ranked.size());
		assertFalse(ranked.getFirst().equals(origin));
		assertEquals(origin, ranked.getLast());
	}

	@Test
	void rankingUsesStaticFeasibilityWithoutReachOrLineOfSight() {
		GoalPosition origin = position(0, 64, 0);
		GoalPosition feasible = position(1, 64, 0);
		GoalPosition blockedTarget = position(0, 64, 1);
		GoalPosition missingSupport = position(-1, 64, 0);

		List<GoalPosition> ranked = PortableTablePlacementPolicy.rankFeasibleSites(
			origin,
			List.of(
				observation(feasible, true, false),
				new PortableTablePlacementPolicy.SiteObservation(blockedTarget, true, false, true, false, false),
				new PortableTablePlacementPolicy.SiteObservation(missingSupport, true, true, false, false, false)
			),
			PortableTablePlacementPolicy.MAX_ATTEMPTS
		);

		assertEquals(List.of(feasible), ranked);
	}

	@Test
	void attemptsAdvanceDeterministicallyAndTimeoutIsBounded() {
		GoalPosition first = position(1, 64, 0);
		GoalPosition second = position(0, 64, 1);
		PortableTablePlacementPolicy.AttemptState state = new PortableTablePlacementPolicy.AttemptState(
			List.of(first, second),
			100L
		);

		assertEquals(first, state.activeTarget());
		assertFalse(state.timedOut(100L + PortableTablePlacementPolicy.TIMEOUT_TICKS));

		state = state.advance("safe_stand_position_not_found");
		assertEquals(second, state.activeTarget());
		assertEquals("safe_stand_position_not_found", state.lastFailure());

		state = state.advance("target_not_visible");
		assertTrue(state.exhausted());
		assertTrue(state.timedOut(101L + PortableTablePlacementPolicy.TIMEOUT_TICKS));
	}

	@ParameterizedTest
	@MethodSource("typedFailureDispositionCases")
	void typedChildFailureCodesKeepCurrentRetryAndTerminalBehavior(
		TaskFailureCode failureCode,
		PortableTablePlacementPolicy.FailureDisposition expected
	) {
		PortableTablePlacementPolicy.FailureDecision decision = PortableTablePlacementPolicy.decideFailure(
			attemptState(),
			TaskExecutionState.FAILED,
			failureCode,
			null,
			"safe_stand_position_not_found"
		);

		assertEquals(expected, decision.disposition());
		assertEquals(expected == PortableTablePlacementPolicy.FailureDisposition.RETRY_NEXT_SITE ? 1 : 0, decision.nextState().candidateIndex());
	}

	@Test
	void completedChildRetriesWhenItDidNotPlaceTheExpectedTable() {
		PortableTablePlacementPolicy.FailureDecision decision = PortableTablePlacementPolicy.decideFailure(
			attemptState(),
			TaskExecutionState.COMPLETED,
			TaskFailureCode.NONE,
			TaskTerminationCause.GOAL_REACHED,
			"required_item_missing"
		);

		assertEquals(PortableTablePlacementPolicy.FailureDisposition.RETRY_NEXT_SITE, decision.disposition());
	}

	@ParameterizedTest
	@MethodSource("detailWordCases")
	void detailWordsCannotChangeTypedRetryBehavior(TaskFailureCode failureCode, String detail) {
		PortableTablePlacementPolicy.FailureDecision decision = PortableTablePlacementPolicy.decideFailure(
			attemptState(),
			TaskExecutionState.FAILED,
			failureCode,
			null,
			detail
		);

		assertEquals(expectedDisposition(failureCode), decision.disposition());
		if (expectedDisposition(failureCode) == PortableTablePlacementPolicy.FailureDisposition.RETRY_NEXT_SITE) {
			assertEquals(detail, decision.nextState().lastFailure());
		}
	}

	@Test
	void unknownChildCodeUsesTerminalFallback() {
		PortableTablePlacementPolicy.FailureDecision decision = PortableTablePlacementPolicy.decideFailure(
			attemptState(),
			TaskExecutionState.FAILED,
			null,
			null,
			"safe_stand_position_not_found"
		);

		assertEquals(PortableTablePlacementPolicy.FailureDisposition.TERMINATE, decision.disposition());
	}

	private static Stream<Arguments> typedFailureDispositionCases() {
		return Stream.of(
			Arguments.of(TaskFailureCode.TRANSIENT, PortableTablePlacementPolicy.FailureDisposition.RETRY_NEXT_SITE),
			Arguments.of(TaskFailureCode.MISSING_FACT, PortableTablePlacementPolicy.FailureDisposition.RETRY_NEXT_SITE),
			Arguments.of(TaskFailureCode.ENVIRONMENT_CHANGED, PortableTablePlacementPolicy.FailureDisposition.RETRY_NEXT_SITE),
			Arguments.of(TaskFailureCode.INVALID_ACTION, PortableTablePlacementPolicy.FailureDisposition.RETRY_NEXT_SITE),
			Arguments.of(TaskFailureCode.DESTRUCTIVE_DENIED, PortableTablePlacementPolicy.FailureDisposition.RETRY_NEXT_SITE),
			Arguments.of(TaskFailureCode.MISSING_ITEM, PortableTablePlacementPolicy.FailureDisposition.TERMINATE),
			Arguments.of(TaskFailureCode.BUSY, PortableTablePlacementPolicy.FailureDisposition.TERMINATE),
			Arguments.of(TaskFailureCode.UNKNOWN, PortableTablePlacementPolicy.FailureDisposition.TERMINATE),
			Arguments.of(TaskFailureCode.NONE, PortableTablePlacementPolicy.FailureDisposition.TERMINATE)
		);
	}

	private static Stream<Arguments> detailWordCases() {
		return Stream.of(
			Arguments.of(TaskFailureCode.MISSING_FACT, "required_item_missing world_unavailable interaction_busy"),
			Arguments.of(TaskFailureCode.MISSING_ITEM, "safe_stand_position_not_found"),
			Arguments.of(TaskFailureCode.TRANSIENT, "world_unavailable"),
			Arguments.of(TaskFailureCode.BUSY, "safe_stand_position_not_found")
		);
	}

	private static PortableTablePlacementPolicy.FailureDisposition expectedDisposition(TaskFailureCode code) {
		return switch (code) {
			case TRANSIENT, MISSING_FACT, ENVIRONMENT_CHANGED, INVALID_ACTION, DESTRUCTIVE_DENIED -> PortableTablePlacementPolicy.FailureDisposition.RETRY_NEXT_SITE;
			case MISSING_ITEM, BUSY, UNKNOWN, NONE -> PortableTablePlacementPolicy.FailureDisposition.TERMINATE;
		};
	}

	private static PortableTablePlacementPolicy.AttemptState attemptState() {
		return new PortableTablePlacementPolicy.AttemptState(List.of(position(1, 64, 0), position(0, 64, 1)), 100L);
	}

	private static PortableTablePlacementPolicy.SiteObservation observation(
		GoalPosition target,
		boolean feasible,
		boolean playerOccupied
	) {
		return new PortableTablePlacementPolicy.SiteObservation(
			target,
			true,
			feasible,
			feasible,
			playerOccupied,
			false
		);
	}

	private static GoalPosition position(int x, int y, int z) {
		return new GoalPosition(x, y, z, true);
	}
}
