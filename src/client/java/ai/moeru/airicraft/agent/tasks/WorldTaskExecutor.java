package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.session.SessionSnapshot;

import java.util.Optional;
import java.util.UUID;

public interface WorldTaskExecutor {
	Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask);

	default void onPlayerItemPickupObserved(
		int entityId,
		UUID entityUuid,
		String itemId,
		int pickupDelta,
		int agentAttributedQuantity,
		UUID collectorIdentity,
		UUID observationId
	) {
	}

	TaskExecutionSnapshot snapshot();

	/** False while cancellation still has an outstanding physical effect to release. */
	default boolean released() { return true; }

	void onWorldLeave();

	void shutdown();
}
