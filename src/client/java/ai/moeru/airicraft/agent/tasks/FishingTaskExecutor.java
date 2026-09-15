package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.session.SessionSnapshot;

import java.util.Optional;
import java.util.UUID;

/** A single cast. A bite is observed on the client tick, not by a remote polling loop. */
public final class FishingTaskExecutor implements WorldTaskExecutor {
	private final Environment environment;
	private WorldTaskRequest request;
	private FishOnceStepArgs args;
	private UUID ownedHook;
	private boolean castRequested, retrieving, bite, emitted;
	private int ticks, releaseTicks, settleTicks;
	private String reason;
	private boolean success;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public FishingTaskExecutor(CameraController camera) {
		this(new MinecraftFishingEnvironment(camera));
	}

	FishingTaskExecutor(Environment environment) {
		this.environment = environment;
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot session, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.FISH_ONCE) {
			cleanup();
			if (released()) snapshot = TaskExecutionSnapshot.idle();
			return Optional.empty();
		}
		WorldTaskRequest next = activeTask.get();
		if (request == null || !request.taskId().equals(next.taskId())) {
			cleanup();
			if (!released()) return Optional.empty();
			request = next;
			args = ((WorldTaskRequest.FishOnce) next.task()).args();
			ownedHook = null;
			castRequested = retrieving = bite = emitted = false;
			ticks = releaseTicks = settleTicks = 0;
			reason = environment.validate(args);
			success = false;
		}
		if (emitted) return Optional.empty();
		if (reason != null) return finish();
		if (!session.companionActuationAllowed()) {
			cleanup();
			setSnapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, "session_gate");
			return Optional.empty();
		}
		ticks++;
		if (!castRequested) {
			retrieving = false;
			releaseTicks = 0;
			if (!environment.cast(args)) {
				reason = "fishing_cast_refused";
				return finish();
			}
			castRequested = true;
			ownedHook = environment.hookId();
		}
		if (ownedHook == null) ownedHook = environment.hookId();
		if (ownedHook != null && !ownedHook.equals(environment.hookId())) {
			reason = "fishing_hook_lost";
		}
		else if (ownedHook != null && environment.biting()) {
			bite = true;
			success = true;
			reason = "bite_retrieved";
		}
		else if (ownedHook != null && environment.hookedEntity()) {
			reason = "fishing_hooked_entity";
		}
		else if (ticks >= 100 && (ownedHook == null || !environment.inWater())) {
			reason = "fishing_cast_not_in_water";
		}
		else if (ticks >= args.maxWaitTicks()) {
			success = true;
			reason = "fishing_wait_budget_elapsed";
		}
		if (reason != null) return finish();
		setSnapshot(TaskExecutionState.RUNNING, ownedHook == null ? "awaiting_cast" : "waiting_for_bite");
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> finish() {
		cleanup();
		if (!released()) return Optional.empty();
		// Allow the retrieved item to reach the player; inventory is the evidence of actual yield.
		if (bite && ++settleTicks < 20) {
			setSnapshot(TaskExecutionState.RUNNING, "collecting_retrieved_item");
			return Optional.empty();
		}
		TaskExecutionState state = success ? TaskExecutionState.COMPLETED : TaskExecutionState.FAILED;
		setSnapshot(state, reason + " biteObserved=" + bite);
		emitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, state,
			reason + " biteObserved=" + bite, success ? TaskTerminationCause.GOAL_REACHED : null,
			success ? null : TaskFailureCode.MISSING_FACT));
	}

	private void cleanup() {
		if (!castRequested) return;
		releaseTicks++;
		if (ownedHook == null) ownedHook = environment.hookId();
		if (ownedHook != null && ownedHook.equals(environment.hookId())) {
			if (!retrieving || releaseTicks % 20 == 0) {
				environment.reel(ownedHook);
				retrieving = true;
			}
			setSnapshot(TaskExecutionState.RUNNING, "waiting_for_hook_release");
		}
		else if (ownedHook != null) {
			castRequested = false;
			ownedHook = null;
		}
		else setSnapshot(TaskExecutionState.RUNNING, "waiting_for_cast_reconciliation");
	}

	@Override public boolean released() { return !castRequested; }
	@Override public TaskExecutionSnapshot snapshot() { return snapshot; }
	private void setSnapshot(TaskExecutionState state, String message) {
		snapshot = new TaskExecutionSnapshot(state, request == null ? null : request.taskId(), null,
			"Fishing", message + " ticks=" + ticks, null, null);
	}
	@Override public void onWorldLeave() {
		castRequested = false;
		ownedHook = null;
		request = null;
		snapshot = TaskExecutionSnapshot.idle();
	}
	@Override public void shutdown() { cleanup(); }

	interface Environment {
		String validate(FishOnceStepArgs args);
		UUID hookId();
		boolean biting();
		boolean inWater();
		boolean hookedEntity();
		boolean cast(FishOnceStepArgs args);
		void reel(UUID hookId);
	}
}
