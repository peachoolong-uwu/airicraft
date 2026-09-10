package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.AcquisitionConstraints;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** System 1 owns acquisition. Baritone receives only an observed work position. */
public final class TargetAcquisitionTaskExecutor implements WorldTaskExecutor {
	private final BaritoneFacade navigation;
	private final Environment environment;
	private WorldTaskRequest request;
	private AcquisitionConstraints constraints;
	private Candidate target;
	private Phase phase = Phase.SELECT;
	private final Set<String> rejected = new HashSet<>();
	private int activeTicks;
	private int phaseTicks;
	private int progressTicks;
	private double bestDistance;
	private boolean navigationOwned;
	private TaskTerminalEvent terminal;
	private boolean emitted;
	private String lastRejection;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public TargetAcquisitionTaskExecutor(BaritoneFacade navigation) {
		this(navigation, new MinecraftAcquisitionEnvironment());
	}

	TargetAcquisitionTaskExecutor(BaritoneFacade navigation, Environment environment) {
		this.navigation = Objects.requireNonNull(navigation);
		this.environment = Objects.requireNonNull(environment);
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot session, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty()) {
			release();
			// A reflex temporarily withdraws the same request. Keep its scope and budgets;
			// a replacement task identity or world leave discards the continuation.
			snapshot = TaskExecutionSnapshot.idle();
			return Optional.empty();
		}
		WorldTaskRequest next = activeTask.orElseThrow();
		if (!session.companionActuationAllowed()) {
			release();
			snapshot = new TaskExecutionSnapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, next.taskId(), next.goal(),
				"TargetAcquisition", "session_gate", null, null);
			return Optional.empty();
		}
		if (request == null || !request.taskId().equals(next.taskId())) {
			release();
			request = next;
			constraints = next.goal().mineSpec().constraints().anchoredAt(environment.position());
			rejected.clear();
			lastRejection = "none";
			activeTicks = 0;
			target = null;
			terminal = null;
			emitted = false;
			enter(Phase.SELECT);
		}
		request = next;
		if (terminal != null) return finishRelease();
		activeTicks++;
		phaseTicks++;
		GoalMineSpec spec = request.goal().mineSpec();
		int count = environment.inventoryCount(spec);
		if (count >= spec.quantity()) return finish(true, "inventory_target_reached itemCount=" + count);
		if (activeTicks > 2400) return finish(false, "acquisition_budget_exhausted itemCount=" + count);
		if (!environment.inScope(environment.position(), constraints, true))
			return finish(false, "acquisition_scope_left itemCount=" + count);
		if (!BaritoneReleaseBarrier.released(navigation) && !navigationOwned) {
			if (phaseTicks > 100) return finish(false, "acquisition_release_timeout");
			setSnapshot(TaskExecutionState.RUNNING, "waiting_for_navigation_release");
			return Optional.empty();
		}
		if (phase == Phase.SETTLE) {
			if (phaseTicks >= 20) enter(Phase.SELECT);
		}
		else if (phase == Phase.SELECT) {
			List<Candidate> candidates = environment.candidates(spec, constraints, rejected);
			if (candidates.isEmpty()) {
				boolean brokenEnough = ((WorldTaskRequest.Mine) request.task()).mineGoalSatisfied();
				return finish(brokenEnough, (brokenEnough ? "requested_blocks_broken" : "no_reachable_resource_in_scope")
					+ " itemCount=" + count + " rejectedTargets=" + rejected.size() + " lastRejection=" + lastRejection);
			}
			target = candidates.getFirst();
			if (((WorldTaskRequest.Mine) request.task()).mineGoalSatisfied() && target.kind() == Kind.BLOCK)
				return finish(true, "requested_blocks_broken");
			enter(Phase.APPROACH);
			bestDistance = distanceSquared(environment.position(), target.workPosition());
			progressTicks = 0;
		}
		else if (!environment.targetPresent(target)) {
			release();
			enter(Phase.SETTLE);
		}
		else if (phase == Phase.APPROACH) {
			boolean reached = environment.canInteract(target);
			if (reached) {
				release();
				enter(target.kind() == Kind.BLOCK ? Phase.BREAK : Phase.PICKUP);
			}
			else if (phaseTicks > 240 || progressTicks > 80) reject("approach_stalled");
			else {
				double distance = distanceSquared(environment.position(), target.workPosition());
				if (distance < bestDistance - 1) { bestDistance = distance; progressTicks = 0; }
				else progressTicks++;
				if (!navigationOwned) {
					navigation.pollPathEvent();
					navigation.startNavigate(target.workPosition());
					navigationOwned = true;
				}
				String event = navigation.pollPathEvent().orElse("");
				if (event.contains("FAIL") || event.equals("CANCELED") || event.equals("CANCELLED")) reject("approach_" + event);
			}
		}
		else if (phase == Phase.BREAK) {
			if (phaseTicks > 240) reject("break_timeout");
			else {
				BreakResult result = environment.breakTarget(target, spec);
				if (result == BreakResult.FAILED) reject("break_unavailable");
				else if (result == BreakResult.BROKEN) {
					environment.cancelBreaking();
					enter(Phase.SETTLE);
				}
			}
		}
		else if (phase == Phase.PICKUP && phaseTicks > 40) reject("pickup_not_collected");
		setSnapshot(TaskExecutionState.RUNNING, "acquisition phase=" + phase + " target="
			+ (target == null ? "none" : target.id() + "@" + target.position())
			+ " itemCount=" + count + " targetCount=" + spec.quantity() + " rejected=" + rejected.size() + " lastRejection=" + lastRejection);
		return Optional.empty();
	}

	private void reject(String reason) {
		rejected.add(target.key());
		lastRejection = reason;
		release();
		enter(Phase.SELECT);
		setSnapshot(TaskExecutionState.RUNNING, reason + " target=" + target.position());
	}

	private Optional<TaskTerminalEvent> finish(boolean success, String reason) {
		terminal = new TaskTerminalEvent(request.taskId(), request.goal(),
			success ? TaskExecutionState.COMPLETED : TaskExecutionState.FAILED,
			reason, success ? TaskTerminationCause.GOAL_REACHED : null,
			success ? TaskFailureCode.NONE : TaskFailureCode.MISSING_FACT);
		release();
		enter(Phase.RELEASE);
		return finishRelease();
	}

	private Optional<TaskTerminalEvent> finishRelease() {
		if (!BaritoneReleaseBarrier.releaseAndDrain(navigation)) {
			setSnapshot(TaskExecutionState.RUNNING, "releasing_acquisition_navigation");
			return Optional.empty();
		}
		setSnapshot(terminal.terminalState(), terminal.message());
		if (emitted) return Optional.empty();
		emitted = true;
		return Optional.of(terminal);
	}

	private void enter(Phase next) { phase = next; phaseTicks = 0; }
	private void release() {
		if (navigationOwned) navigation.cancel();
		navigationOwned = false;
		environment.cancelBreaking();
	}
	private void setSnapshot(TaskExecutionState state, String detail) {
		snapshot = new TaskExecutionSnapshot(state, request.taskId(), request.goal(), "TargetAcquisition", detail, null,
			terminal == null ? null : terminal.terminationCause());
	}
	static double distanceSquared(GoalPosition a, GoalPosition b) {
		double x = (double) a.x() - b.x(), y = (double) a.y() - b.y(), z = (double) a.z() - b.z();
		return x*x + y*y + z*z;
	}
	@Override public TaskExecutionSnapshot snapshot() { return snapshot; }
	@Override public void onWorldLeave() { release(); request = null; snapshot = TaskExecutionSnapshot.idle(); }
	@Override public void shutdown() { onWorldLeave(); }

	enum Phase { SELECT, APPROACH, BREAK, PICKUP, SETTLE, RELEASE }
	enum Kind { DROP, BLOCK }
	enum BreakResult { BREAKING, BROKEN, FAILED }
	record Candidate(Kind kind, String id, GoalPosition position, GoalPosition workPosition) {
		String key() { return kind + ":" + id + ":" + position; }
	}
	interface Environment {
		GoalPosition position();
		int inventoryCount(GoalMineSpec spec);
		boolean inScope(GoalPosition position, AcquisitionConstraints constraints, boolean standing);
		List<Candidate> candidates(GoalMineSpec spec, AcquisitionConstraints constraints, Set<String> rejected);
		boolean targetPresent(Candidate target);
		boolean canInteract(Candidate target);
		BreakResult breakTarget(Candidate target, GoalMineSpec spec);
		void cancelBreaking();
	}
}
