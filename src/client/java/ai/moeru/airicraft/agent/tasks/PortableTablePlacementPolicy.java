package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class PortableTablePlacementPolicy {
	static final int MAX_ATTEMPTS = 8;
	static final long TIMEOUT_TICKS = 600L;
	private static final int SEARCH_RADIUS = 2;
	private static final List<Integer> Y_OFFSETS = List.of(0, -1, 1, -2, 2, -3, 3, -4, 4);

	private PortableTablePlacementPolicy() {
	}

	static List<GoalPosition> candidatePositions(GoalPosition origin) {
		Objects.requireNonNull(origin, "origin");
		Set<GoalPosition> candidates = new LinkedHashSet<>();
		for (int yOffset : Y_OFFSETS) {
			for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
				for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
					candidates.add(new GoalPosition(origin.x() + dx, origin.y() + yOffset, origin.z() + dz, true));
				}
			}
		}
		return List.copyOf(candidates);
	}

	static List<GoalPosition> rankFeasibleSites(
		GoalPosition origin,
		List<SiteObservation> observations,
		int limit
	) {
		Objects.requireNonNull(origin, "origin");
		Objects.requireNonNull(observations, "observations");
		if (limit <= 0) {
			return List.of();
		}
		List<SiteObservation> feasible = observations.stream()
			.filter(SiteObservation::feasible)
			.sorted(Comparator
				.comparing(SiteObservation::playerOccupied)
				.thenComparingInt(site -> Math.abs(site.target().y() - origin.y()))
				.thenComparingLong(site -> squaredDistance(origin, site.target()))
				.thenComparingInt(site -> site.target().x())
				.thenComparingInt(site -> site.target().y())
				.thenComparingInt(site -> site.target().z()))
			.toList();
		if (feasible.size() <= limit) {
			return feasible.stream().map(SiteObservation::target).toList();
		}
		ArrayList<SiteObservation> selected = new ArrayList<>(feasible.subList(0, limit));
		feasible.stream()
			.filter(SiteObservation::playerOccupied)
			.findFirst()
			.filter(playerSite -> selected.stream().noneMatch(site -> site.target().equals(playerSite.target())))
			.ifPresent(playerSite -> selected.set(selected.size() - 1, playerSite));
		return selected.stream().map(SiteObservation::target).toList();
	}

	static FailureDecision decideFailure(
		AttemptState attemptState,
		TaskExecutionState terminalState,
		TaskFailureCode failureCode,
		TaskTerminationCause terminationCause,
		String detail
	) {
		Objects.requireNonNull(attemptState, "attemptState");
		FailureDisposition disposition = failureDisposition(terminalState, failureCode, terminationCause);
		AttemptState nextState = disposition == FailureDisposition.RETRY_NEXT_SITE
			? attemptState.advance(detail)
			: attemptState;
		return new FailureDecision(disposition, nextState);
	}

	static FailureDisposition failureDisposition(TaskFailureCode failureCode) {
		return failureDisposition(TaskExecutionState.FAILED, failureCode, null);
	}

	static FailureDisposition failureDisposition(
		TaskExecutionState terminalState,
		TaskFailureCode failureCode,
		TaskTerminationCause terminationCause
	) {
		if (terminalState == TaskExecutionState.COMPLETED && terminationCause == TaskTerminationCause.GOAL_REACHED) {
			return FailureDisposition.RETRY_NEXT_SITE;
		}
		if (terminalState != TaskExecutionState.FAILED) {
			return FailureDisposition.TERMINATE;
		}
		return switch (failureCode == null ? TaskFailureCode.UNKNOWN : failureCode) {
			case TRANSIENT, MISSING_FACT, ENVIRONMENT_CHANGED, INVALID_ACTION, DESTRUCTIVE_DENIED -> FailureDisposition.RETRY_NEXT_SITE;
			case MISSING_ITEM, BUSY, UNKNOWN, NONE -> FailureDisposition.TERMINATE;
		};
	}

	private static long squaredDistance(GoalPosition left, GoalPosition right) {
		long dx = (long) left.x() - right.x();
		long dy = (long) left.y() - right.y();
		long dz = (long) left.z() - right.z();
		return dx * dx + dy * dy + dz * dz;
	}

	record SiteObservation(
		GoalPosition target,
		boolean targetLoaded,
		boolean targetReplaceable,
		boolean adjacentSupportAvailable,
		boolean playerOccupied,
		boolean preserved
	) {
		SiteObservation {
			Objects.requireNonNull(target, "target");
		}

		boolean feasible() {
			return targetLoaded && targetReplaceable && adjacentSupportAvailable && !preserved;
		}
	}

	record AttemptState(
		List<GoalPosition> candidates,
		int candidateIndex,
		long startedTick,
		String lastFailure
	) {
		AttemptState(List<GoalPosition> candidates, long startedTick) {
			this(candidates, 0, startedTick, null);
		}

		AttemptState {
			candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
			if (candidateIndex < 0 || candidateIndex > candidates.size()) {
				throw new IllegalArgumentException("candidateIndex out of bounds");
			}
		}

		GoalPosition activeTarget() {
			return exhausted() ? null : candidates.get(candidateIndex);
		}

		int attemptNumber() {
			return exhausted() ? candidates.size() : candidateIndex + 1;
		}

		boolean exhausted() {
			return candidateIndex >= candidates.size();
		}

		boolean timedOut(long currentTick) {
			return currentTick - startedTick > TIMEOUT_TICKS;
		}

		AttemptState advance(String failure) {
			if (exhausted()) {
				return this;
			}
			return new AttemptState(candidates, candidateIndex + 1, startedTick, failure);
		}
	}

	record FailureDecision(
		FailureDisposition disposition,
		AttemptState nextState
	) {
		FailureDecision {
			Objects.requireNonNull(disposition, "disposition");
			Objects.requireNonNull(nextState, "nextState");
		}
	}

	enum FailureDisposition {
		RETRY_NEXT_SITE,
		TERMINATE
	}
}
