package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.llm.CompactionExecutionResult;
import ai.moeru.airicraft.agent.llm.ExternalPlannerToolResult;
import ai.moeru.airicraft.agent.llm.LlmFailureType;
import ai.moeru.airicraft.agent.llm.PlannerConversationDebugSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerExecutionResult;
import ai.moeru.airicraft.agent.llm.PlannerOrchestrator;
import ai.moeru.airicraft.agent.llm.PlannerOrchestratorDebugSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerRequest;
import ai.moeru.airicraft.agent.llm.PlannerRequestSeed;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import ai.moeru.airicraft.agent.llm.PlannerTrigger;
import ai.moeru.airicraft.agent.llm.PlannerTriggerBatch;
import ai.moeru.airicraft.agent.llm.PlannerTriggerType;
import ai.moeru.airicraft.agent.llm.StalePlannerRejection;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.MissionExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskSnapshot;
import com.google.gson.JsonObject;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class DialogueRuntime {
	private final PlannerOrchestrator plannerOrchestrator;
	private final ai.moeru.airicraft.agent.llm.goal.PlannerGoalStore plannerGoal;
	private long nextGoalContinuationTick;
	private final Clock clock;
	private final int maxRecentTurns;
	private final List<DialogueTurn> recentTurns = new ArrayList<>();
	private final Deque<PendingInternalTaskUpdate> pendingInternalTaskUpdates = new ArrayDeque<>();
	private final Deque<PendingDialogueReply> pendingVisibleReplies = new ArrayDeque<>();

	private DialogueState state = DialogueCore.initialState();
	private int queuedTimeoutInjections;
	private boolean pendingTimeoutVisibleReply;
	private long userGuidanceRevision;
	private long safetyEpoch;
	private String safetyHoldId;
	private boolean reflexActive;
	private boolean externalDriverActive;
	private long nextPendingReplyId = 1L;

	public DialogueRuntime(PlannerOrchestrator plannerOrchestrator, int maxRecentTurns) {
		this(plannerOrchestrator, maxRecentTurns, Clock.systemDefaultZone());
	}

	public DialogueRuntime(PlannerOrchestrator plannerOrchestrator, int maxRecentTurns, Clock clock) {
		this(plannerOrchestrator, maxRecentTurns, clock, null);
	}

	public DialogueRuntime(PlannerOrchestrator plannerOrchestrator, int maxRecentTurns, Clock clock,
		ai.moeru.airicraft.agent.llm.goal.PlannerGoalStore plannerGoal) {
		this.plannerGoal = plannerGoal;
		this.plannerOrchestrator = plannerOrchestrator;
		this.clock = clock;
		this.maxRecentTurns = Math.max(1, maxRecentTurns);
	}

	public void refreshPlannerGoalWorld() {
		if (plannerGoal != null) plannerGoal.refreshWorld();
	}

	/** Called after action/reflex updates. Returning true reserves initiative for the current goal. */
	public boolean continuePlannerGoal(long tick, boolean workIdle, SessionSnapshot session,
		String primaryPlayer, Optional<GoalSnapshot> actionGoal, TaskSnapshot task,
		MissionExecutionSnapshot mission, SemanticEventBuffer events) {
		if (plannerGoal == null || !plannerGoal.active()) return false;
		if (!workIdle || externalDriverActive || !plannerEnabled() || isDegraded() || !llmAvailable()
			|| reflexActive || session == null || !session.companionActuationAllowed()
			|| plannerOrchestrator.hasInFlight() || !pendingInternalTaskUpdates.isEmpty()) {
			nextGoalContinuationTick = tick + 20;
			return true;
		}
		if (tick < nextGoalContinuationTick) return true;
		nextGoalContinuationTick = tick + 20;
		onPlannerTrigger(PlannerTrigger.autonomous(PlannerTriggerType.SYSTEM, "self",
			"GOAL CONTINUATION: No action is running. Review fresh evidence and advance the active planner goal, "
				+ "change it if appropriate, or finish explicitly with success/give_up. A prior plaintext reply did not end it.\n"
				+ plannerGoal.context(), tick, clock.millis(), "planner_goal"),
			session, primaryPlayer, actionGoal, task, mission, events);
		return true;
	}

	public Optional<DialogueResponse> lastResponse() {
		return Optional.ofNullable(state.lastResponse());
	}

	public boolean hasPendingReply() {
		return !pendingVisibleReplies.isEmpty();
	}

	public String pendingReplyReason() {
		return pendingVisibleReplies.isEmpty() ? state.pendingReplyReason() : pendingVisibleReplies.peekFirst().reason();
	}

	public Optional<PendingDialogueReply> pendingReplyReady(long tick) {
		if (pendingVisibleReplies.isEmpty()) {
			return Optional.empty();
		}
		PendingDialogueReply pendingReply = pendingVisibleReplies.peekFirst();
		if (tick < pendingReply.readyTick()) {
			return Optional.empty();
		}
		return Optional.of(pendingReply);
	}

	public boolean recordSentReply(PendingDialogueReply sentReply, boolean sendSucceeded) {
		if (!sendSucceeded || sentReply == null || pendingVisibleReplies.isEmpty()) {
			return false;
		}

		PendingDialogueReply pendingReply = pendingVisibleReplies.peekFirst();
		if (
			pendingReply.id() != sentReply.id()
				|| !Objects.equals(pendingReply.response().text(), sentReply.response().text())
		) {
			return false;
		}

		pendingVisibleReplies.removeFirst();
		recordAgentTurn(pendingReply.response().text(), pendingReply.response().tick());
		state = state.withPendingReply(!pendingVisibleReplies.isEmpty(), pendingReplyReason());
		return true;
	}

	public boolean isDegraded() {
		return state.degraded();
	}

	public boolean llmAvailable() {
		return plannerOrchestrator.isConfigured();
	}

	public boolean plannerEnabled() {
		return plannerOrchestrator.isEnabled();
	}

	public void setPlannerEnabled(boolean enabled) {
		plannerOrchestrator.setEnabled(enabled);
		if (enabled) {
			return;
		}
		queuedTimeoutInjections = 0;
		pendingTimeoutVisibleReply = false;
		pendingInternalTaskUpdates.clear();
		pendingVisibleReplies.clear();
		state = state.withPendingReply(false, null);
	}

	public void enableExternalDriver() {
		externalDriverActive = true;
		queuedTimeoutInjections = 0;
		pendingTimeoutVisibleReply = false;
		pendingInternalTaskUpdates.clear();
		plannerOrchestrator.reset();
	}

	public boolean externalDriverActive() {
		return externalDriverActive;
	}

	public List<Map<String, Object>> allAvailableTools() {
		return plannerOrchestrator.allAvailableTools();
	}

	public CompletableFuture<ExternalPlannerToolResult> executeExternalTool(String name, JsonObject arguments) {
		return plannerOrchestrator.executeExternalTool(name, arguments);
	}

	public PlannerOrchestratorDebugSnapshot plannerDebugSnapshot() {
		return plannerOrchestrator.debugSnapshot();
	}

	public PlannerConversationDebugSnapshot plannerConversationDebugSnapshot() {
		return plannerOrchestrator.conversationDebugSnapshot();
	}

	public PlannerConversationDebugSnapshot plannerProjectedConversationDebugSnapshot() {
		return plannerOrchestrator.projectedConversationDebugSnapshot();
	}

	public PlannerConversationDebugSnapshot plannerCanonicalConversationDebugSnapshot() {
		return plannerOrchestrator.canonicalConversationDebugSnapshot();
	}

	public List<String> plannerContextExcerpt() {
		return plannerOrchestrator.contextExcerpt();
	}

	public void invalidateIdleThinkTriggers() {
		plannerOrchestrator.invalidateIdleThinkTriggers();
	}

	public void updateSafetyContext(long replacementSafetyEpoch, String replacementSafetyHoldId, boolean activeReflex) {
		safetyEpoch = Math.max(safetyEpoch, Math.max(0L, replacementSafetyEpoch));
		safetyHoldId = replacementSafetyHoldId;
		reflexActive = activeReflex;
		plannerOrchestrator.updateSafetyContext(safetyEpoch, safetyHoldId, reflexActive);
	}

	public List<StalePlannerRejection> drainStalePlannerRejections() {
		return plannerOrchestrator.drainStalePlannerRejections();
	}

	public boolean startDebugCompaction() {
		return plannerOrchestrator.startDebugCompaction();
	}

	public CompactionExecutionResult pollDebugCompaction() {
		return plannerOrchestrator.pollDebugCompaction();
	}

	public long lastFailureTick() {
		return state.lastFailureTick();
	}

	public int consecutiveFailureCount() {
		return state.consecutiveFailureCount();
	}

	public LlmFailureType lastFailureType() {
		return state.lastFailureType();
	}

	public DialogueSnapshot snapshot() {
		return new DialogueSnapshot(
			List.copyOf(recentTurns),
			state.lastResponse(),
			hasPendingReply(),
			pendingReplyReason(),
			state.degraded(),
			state.consecutiveFailureCount(),
			state.lastFailureType(),
			state.lastFailureTick()
		);
	}

	public void injectMockResponse(PlannerResponse response) {
		plannerOrchestrator.injectMockResponse(response);
	}

	public void injectTimeout() {
		if (plannerOrchestrator.isEnabled()) {
			queuedTimeoutInjections++;
		}
	}

	public boolean handleResetCommand(String senderName, String plainTextMessage, long tick, SemanticEventBuffer eventBuffer) {
		if (!DialogueCore.isResetCommand(plainTextMessage)) {
			return false;
		}

		appendTurn(new DialogueTurn(senderName, plainTextMessage, tick, clock.millis()));
		supersedePendingInternalTaskUpdates("planner_reset", tick, eventBuffer);
		plannerOrchestrator.reset();
		queuedTimeoutInjections = 0;
		applyTransition(DialogueCore.onReset(state, senderName, tick), tick, eventBuffer);
		return true;
	}

	public void onPlayerChat(
		String senderName,
		String plainTextMessage,
		long tick,
		SessionSnapshot sessionSnapshot,
		String primaryInteractionPlayer,
		Optional<GoalSnapshot> activeGoal,
		TaskSnapshot activeTask,
		MissionExecutionSnapshot missionExecution,
		SemanticEventBuffer eventBuffer
	) {
		long timestampMs = clock.millis();
		appendTurn(new DialogueTurn(senderName, plainTextMessage, tick, timestampMs));
		submitPlannerTrigger(
			new PlannerRequest(
				tick,
				timestampMs,
				sessionSnapshot.mode(),
				primaryInteractionPlayer,
				activeGoal.orElse(null),
				activeTask,
				missionExecution,
				senderName,
				plainTextMessage,
				null
			),
			eventBuffer,
			timestampMs,
			true
		);
	}

	public void onContextTrigger(
		PlannerTriggerType triggerType,
		String senderName,
		String plainTextMessage,
		long tick,
		SessionSnapshot sessionSnapshot,
		String primaryInteractionPlayer,
		Optional<GoalSnapshot> activeGoal,
		SemanticEventBuffer eventBuffer
	) {
		long timestampMs = clock.millis();
		submitPlannerTrigger(
			PlannerRequest.ofTrigger(
				tick,
				timestampMs,
				sessionSnapshot.mode(),
				primaryInteractionPlayer,
				activeGoal.orElse(null),
				triggerType,
				senderName,
				plainTextMessage,
				null
			),
			eventBuffer,
			timestampMs,
			triggerType == PlannerTriggerType.CHAT && senderName != null && !"system".equalsIgnoreCase(senderName)
		);
	}

	public void onPlannerTrigger(
		PlannerTrigger trigger,
		SessionSnapshot sessionSnapshot,
		String primaryInteractionPlayer,
		Optional<GoalSnapshot> activeGoal,
		TaskSnapshot activeTask,
		MissionExecutionSnapshot missionExecution,
		SemanticEventBuffer plannerEventBuffer
	) {
		if (trigger == null) {
			return;
		}
		submitPlannerTrigger(
			new PlannerRequest(
				trigger.tick(),
				trigger.timestampMs(),
				sessionSnapshot.mode(),
				primaryInteractionPlayer,
				activeGoal.orElse(null),
				activeTask,
				missionExecution,
				PlannerTriggerBatch.of(List.of(trigger)),
				null
			),
			plannerEventBuffer,
			trigger.timestampMs(),
			trigger.maySupersedeLaunchedTurn()
		);
	}

	public void onPlayerChat(
		String senderName,
		String plainTextMessage,
		long tick,
		SessionSnapshot sessionSnapshot,
		String primaryInteractionPlayer,
		Optional<GoalSnapshot> activeGoal,
		SemanticEventBuffer eventBuffer
	) {
		onPlayerChat(senderName, plainTextMessage, tick, sessionSnapshot, primaryInteractionPlayer, activeGoal, null, null, eventBuffer);
	}

	public void onInternalTaskUpdate(
		String updateMessage,
		long tick,
		SessionSnapshot sessionSnapshot,
		Optional<GoalSnapshot> activeGoal,
		TaskSnapshot activeTask,
		MissionExecutionSnapshot missionExecution,
		SemanticEventBuffer eventBuffer
	) {
		long timestampMs = clock.millis();
		appendTurn(new DialogueTurn("system", updateMessage, tick, timestampMs));
		if (externalDriverActive || (state.degraded() && plannerOrchestrator.isEnabled()) || !plannerOrchestrator.isConfigured()) {
			return;
		}
		PendingInternalTaskUpdate pendingUpdate = new PendingInternalTaskUpdate(
			updateMessage,
			tick,
			timestampMs,
			userGuidanceRevision,
			missionId(activeTask, missionExecution),
			sessionSnapshot == null ? SessionSnapshot.initial() : sessionSnapshot,
			activeGoal.orElse(null),
			activeTask,
			missionExecution
		);
		if (!submitInternalTaskUpdate(pendingUpdate, eventBuffer)) {
			pendingInternalTaskUpdates.addLast(pendingUpdate);
		}
	}

	public void onInternalTaskUpdate(
		String updateMessage,
		long tick,
		SessionSnapshot sessionSnapshot,
		Optional<GoalSnapshot> activeGoal,
		SemanticEventBuffer eventBuffer
	) {
		onInternalTaskUpdate(updateMessage, tick, sessionSnapshot, activeGoal, null, null, eventBuffer);
	}

	public DialogueResponse poll(long tick, SemanticEventBuffer eventBuffer) {
		return poll(tick, eventBuffer, null, Optional.empty(), null, null);
	}

	public DialogueResponse poll(
		long tick,
		SemanticEventBuffer eventBuffer,
		SessionSnapshot sessionSnapshot,
		Optional<GoalSnapshot> activeGoal,
		TaskSnapshot activeTask,
		MissionExecutionSnapshot missionExecution
	) {
		if (externalDriverActive) {
			return null;
		}
		if (queuedTimeoutInjections > 0 && !plannerOrchestrator.hasInFlight()) {
			queuedTimeoutInjections--;
			applyTransition(DialogueCore.onPlannerFailure(state, LlmFailureType.TIMEOUT, "Injected LLM timeout", pendingTimeoutVisibleReply, tick), tick, eventBuffer);
			pendingTimeoutVisibleReply = false;
			return null;
		}
		if (submitNextPendingInternalTaskUpdate(eventBuffer, sessionSnapshot, activeGoal, activeTask, missionExecution)) {
			return null;
		}

		PlannerExecutionResult result = plannerOrchestrator.poll();
		if (result == null) {
			return null;
		}

		if (!result.succeeded()) {
			boolean timeoutVisibleReply = pendingTimeoutVisibleReply || isDirectChatRequest(result.request());
			applyTransition(
				DialogueCore.onPlannerFailure(
					state,
					result.failureType(),
					result.failureMessage(),
					timeoutVisibleReply,
					tick
				),
				tick,
				eventBuffer
			);
			pendingTimeoutVisibleReply = false;
			submitNextPendingInternalTaskUpdate(eventBuffer, sessionSnapshot, activeGoal, activeTask, missionExecution);
			return null;
		}

		DialogueTransition transition = DialogueCore.onPlannerSuccess(state, result.response(), tick);
		applyTransition(transition, tick, eventBuffer);
		pendingTimeoutVisibleReply = false;
		plannerOrchestrator.onAcceptedReplyRecorded();
		submitNextPendingInternalTaskUpdate(eventBuffer, sessionSnapshot, activeGoal, activeTask, missionExecution);
		return transition.lastVisibleResponse();
	}

	public void resetLlmState(long tick, SemanticEventBuffer eventBuffer) {
		plannerOrchestrator.reset();
		plannerOrchestrator.updateSafetyContext(safetyEpoch, safetyHoldId, reflexActive);
		queuedTimeoutInjections = 0;
		pendingTimeoutVisibleReply = false;
		pendingInternalTaskUpdates.clear();
		pendingVisibleReplies.clear();
		userGuidanceRevision = 0L;
		if (state.degraded()) {
			applyEffects(List.of(DialogueEffect.appendSemanticEvent("planner.degraded_cleared", java.util.Map.of())), tick, eventBuffer);
		}
		state = DialogueCore.initialState();
	}

	public void clear() {
		state = DialogueCore.initialState();
		queuedTimeoutInjections = 0;
		pendingTimeoutVisibleReply = false;
		pendingInternalTaskUpdates.clear();
		pendingVisibleReplies.clear();
		recentTurns.clear();
		userGuidanceRevision = 0L;
		plannerOrchestrator.reset();
		plannerOrchestrator.updateSafetyContext(safetyEpoch, safetyHoldId, reflexActive);
	}

	public void shutdown() {
		state = DialogueCore.initialState();
		queuedTimeoutInjections = 0;
		pendingTimeoutVisibleReply = false;
		pendingInternalTaskUpdates.clear();
		pendingVisibleReplies.clear();
		recentTurns.clear();
		userGuidanceRevision = 0L;
		plannerOrchestrator.shutdown();
	}

	public static boolean isResetCommand(String plainTextMessage) {
		return DialogueCore.isResetCommand(plainTextMessage);
	}

	private void submitPlannerTrigger(
		PlannerRequest request,
		SemanticEventBuffer eventBuffer,
		long timestampMs,
		boolean directUserGuidance
	) {
		if (externalDriverActive) {
			return;
		}
		request = request.withSafetyContext(safetyEpoch, safetyHoldId);
		if (directUserGuidance) {
			supersedePendingInternalTaskUpdates("new_user_guidance", request.tick(), eventBuffer);
		}
		if (state.degraded() && plannerOrchestrator.isEnabled()) {
			applyTransition(
				DialogueCore.onPlannerDegradedBlocked(state, request.senderName(), directUserGuidance, request.tick()),
				request.tick(),
				eventBuffer
			);
			return;
		}
		Long sinceSeqNo = plannerOrchestrator.lastObservedEventSeqNo();
		plannerOrchestrator.recordEvents(
			eventBuffer.query(sinceSeqNo <= 0L ? null : sinceSeqNo),
			new PlannerRequestSeed(
				request.tick(),
				timestampMs,
				request.sessionMode(),
				request.primaryInteractionPlayer(),
				request.activeGoal()
			)
		);
		boolean submitted = plannerOrchestrator.submit(request);
		pendingTimeoutVisibleReply = directUserGuidance && submitted;
	}

	private boolean submitNextPendingInternalTaskUpdate(
		SemanticEventBuffer eventBuffer,
		SessionSnapshot sessionSnapshot,
		Optional<GoalSnapshot> activeGoal,
		TaskSnapshot activeTask,
		MissionExecutionSnapshot missionExecution
	) {
		if (externalDriverActive || pendingInternalTaskUpdates.isEmpty()) {
			return false;
		}
		if ((state.degraded() && plannerOrchestrator.isEnabled()) || !plannerOrchestrator.isConfigured()) {
			pendingInternalTaskUpdates.clear();
			return false;
		}
		if (plannerOrchestrator.hasInFlight()) {
			return false;
		}
		while (!pendingInternalTaskUpdates.isEmpty()) {
			PendingInternalTaskUpdate pendingUpdate = pendingInternalTaskUpdates.removeFirst();
			String currentMissionId = missionId(activeTask, missionExecution);
			if (pendingUpdate.userGuidanceRevision() != userGuidanceRevision) {
				recordSupersededInternalTaskUpdate(
					pendingUpdate,
					"new_user_guidance",
					currentMissionId,
					pendingUpdate.tick(),
					eventBuffer
				);
				continue;
			}
			if (
				pendingUpdate.missionId() != null
					&& currentMissionId != null
					&& !Objects.equals(pendingUpdate.missionId(), currentMissionId)
			) {
				recordSupersededInternalTaskUpdate(
					pendingUpdate,
					"mission_changed",
					currentMissionId,
					pendingUpdate.tick(),
					eventBuffer
				);
				continue;
			}
			pendingUpdate = pendingUpdate.withCurrentContext(sessionSnapshot, activeGoal, activeTask, missionExecution);
			if (submitInternalTaskUpdate(pendingUpdate, eventBuffer)) {
				return true;
			}
			pendingInternalTaskUpdates.addFirst(pendingUpdate);
			return false;
		}
		return false;
	}

	private void supersedePendingInternalTaskUpdates(String reason, long tick, SemanticEventBuffer eventBuffer) {
		userGuidanceRevision++;
		while (!pendingInternalTaskUpdates.isEmpty()) {
			recordSupersededInternalTaskUpdate(pendingInternalTaskUpdates.removeFirst(), reason, null, tick, eventBuffer);
		}
	}

	private void recordSupersededInternalTaskUpdate(
		PendingInternalTaskUpdate pendingUpdate,
		String reason,
		String currentMissionId,
		long supersededAtTick,
		SemanticEventBuffer eventBuffer
	) {
		if (pendingUpdate == null || eventBuffer == null) {
			return;
		}
		eventBuffer.append(supersededAtTick, "planner.internal_task_update_superseded", Map.of(
			"reason", reason,
			"updateTick", pendingUpdate.tick(),
			"supersededAtTick", supersededAtTick,
			"updateGuidanceRevision", pendingUpdate.userGuidanceRevision(),
			"currentGuidanceRevision", userGuidanceRevision,
			"updateMissionId", pendingUpdate.missionId() == null ? "" : pendingUpdate.missionId(),
			"currentMissionId", currentMissionId == null ? "" : currentMissionId
		));
	}

	private static String missionId(TaskSnapshot activeTask, MissionExecutionSnapshot missionExecution) {
		if (
			activeTask != null
				&& activeTask.mission() != null
				&& activeTask.mission().missionId() != null
				&& !activeTask.mission().missionId().isBlank()
		) {
			return activeTask.mission().missionId();
		}
		if (
			missionExecution != null
				&& missionExecution.mission() != null
				&& missionExecution.mission().missionId() != null
				&& !missionExecution.mission().missionId().isBlank()
		) {
			return missionExecution.mission().missionId();
		}
		return null;
	}

	private boolean submitInternalTaskUpdate(PendingInternalTaskUpdate pendingUpdate, SemanticEventBuffer eventBuffer) {
		if (
			externalDriverActive
				|| pendingUpdate == null
				|| (state.degraded() && plannerOrchestrator.isEnabled())
				|| plannerOrchestrator.hasInFlight()
				|| !plannerOrchestrator.isConfigured()
		) {
			return false;
		}
		Long sinceSeqNo = plannerOrchestrator.lastObservedEventSeqNo();
		plannerOrchestrator.recordEvents(
			eventBuffer.query(sinceSeqNo <= 0L ? null : sinceSeqNo),
			new PlannerRequestSeed(
				pendingUpdate.tick(),
				pendingUpdate.timestampMs(),
				pendingUpdate.sessionSnapshot().mode(),
				null,
				pendingUpdate.activeGoal()
			)
		);
		plannerOrchestrator.submit(new PlannerRequest(
			pendingUpdate.tick(),
			pendingUpdate.timestampMs(),
			pendingUpdate.sessionSnapshot().mode(),
			null,
			pendingUpdate.activeGoal(),
			pendingUpdate.activeTask(),
			pendingUpdate.missionExecution(),
			PlannerTriggerBatch.of(List.of(
				PlannerTrigger.pending(PlannerTriggerType.SYSTEM, "runtime", pendingUpdate.updateMessage(), pendingUpdate.tick(), pendingUpdate.timestampMs())
			)),
			null
		).withSafetyContext(safetyEpoch, safetyHoldId));
		pendingTimeoutVisibleReply = false;
		return true;
	}

	private void recordAgentTurn(String text, long tick) {
		DialogueTurn turn = new DialogueTurn(DialogueSpeakerLabels.AGENT, text, tick, clock.millis());
		appendTurn(turn);
		plannerOrchestrator.recordAssistantTurn(turn);
	}

	private void appendTurn(DialogueTurn turn) {
		recentTurns.add(turn);
		while (recentTurns.size() > maxRecentTurns) {
			recentTurns.remove(0);
		}
	}

	private void applyTransition(DialogueTransition transition, long tick, SemanticEventBuffer eventBuffer) {
		state = transition.state();
		applyEffects(transition.effects(), tick, eventBuffer);
		pendingVisibleReplies.clear();
		long nextReadyTick = tick;
		for (DialogueResponse response : transition.visibleResponses()) {
			if (response != null && response.text() != null && !response.text().isBlank()) {
				nextReadyTick += response.delayTicks();
				pendingVisibleReplies.addLast(new PendingDialogueReply(nextPendingReplyId++, response, state.pendingReplyReason(), nextReadyTick));
			}
		}
		state = state.withPendingReply(!pendingVisibleReplies.isEmpty(), pendingReplyReason());
	}

	private static void applyEffects(List<DialogueEffect> effects, long tick, SemanticEventBuffer eventBuffer) {
		for (DialogueEffect effect : effects) {
			if (effect instanceof DialogueEffect.AppendSemanticEvent appendSemanticEvent) {
				eventBuffer.append(tick, appendSemanticEvent.type(), appendSemanticEvent.payload());
			}
		}
	}

	private static boolean isDirectChatRequest(PlannerRequest request) {
		if (request == null || request.triggerBatch() == null || request.triggerBatch().triggers().isEmpty()) {
			return false;
		}
		return request.triggerBatch().triggers().stream().allMatch(trigger ->
			trigger.type() == PlannerTriggerType.CHAT
				&& trigger.maySupersedeLaunchedTurn()
				&& trigger.speaker() != null
				&& !"system".equalsIgnoreCase(trigger.speaker())
		);
	}

	private record PendingInternalTaskUpdate(
		String updateMessage,
		long tick,
		long timestampMs,
		long userGuidanceRevision,
		String missionId,
		SessionSnapshot sessionSnapshot,
		GoalSnapshot activeGoal,
		TaskSnapshot activeTask,
		MissionExecutionSnapshot missionExecution
	) {
		private PendingInternalTaskUpdate withCurrentContext(
			SessionSnapshot currentSessionSnapshot,
			Optional<GoalSnapshot> currentActiveGoal,
			TaskSnapshot currentActiveTask,
			MissionExecutionSnapshot currentMissionExecution
		) {
			return new PendingInternalTaskUpdate(
				updateMessage,
				tick,
				timestampMs,
				userGuidanceRevision,
				missionId,
				currentSessionSnapshot == null ? sessionSnapshot : currentSessionSnapshot,
				currentActiveGoal == null ? activeGoal : currentActiveGoal.orElse(null),
				currentActiveTask == null ? activeTask : currentActiveTask,
				currentMissionExecution == null ? missionExecution : currentMissionExecution
			);
		}
	}

}
