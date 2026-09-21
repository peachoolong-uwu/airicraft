package ai.moeru.airicraft.agent.llm;

import java.util.List;
import java.util.Objects;

public final class CompositePlannerLifecycleListener implements PlannerLifecycleListener {
	private final List<PlannerLifecycleListener> listeners;

	private CompositePlannerLifecycleListener(List<PlannerLifecycleListener> listeners) {
		this.listeners = listeners;
	}

	public static PlannerLifecycleListener of(PlannerLifecycleListener... listeners) {
		Objects.requireNonNull(listeners, "listeners");
		List<PlannerLifecycleListener> normalized = java.util.Arrays.stream(listeners)
			.map(listener -> Objects.requireNonNull(listener, "listener"))
			.filter(listener -> listener != PlannerLifecycleListener.NO_OP)
			.toList();
		if (normalized.isEmpty()) {
			return PlannerLifecycleListener.NO_OP;
		}
		if (normalized.size() == 1) {
			return normalized.getFirst();
		}
		return new CompositePlannerLifecycleListener(normalized);
	}

	@Override
	public void onPlannerTurnSubmitted(PlannerRequest request) {
		listeners.forEach(listener -> listener.onPlannerTurnSubmitted(request));
	}

	@Override
	public void onConversationSubmitted(
		long generation,
		int attempt,
		PlannerSessionPhase phase,
		PlannerRequest request,
		LlmConversation conversation
	) {
		listeners.forEach(listener -> listener.onConversationSubmitted(generation, attempt, phase, request, conversation));
	}

	@Override
	public void onPlannerExecutionSucceeded(PlannerExecutionResult result) {
		listeners.forEach(listener -> listener.onPlannerExecutionSucceeded(result));
	}

	@Override
	public void onPlannerModelCallCompleted(PlannerExecutionResult result) {
		listeners.forEach(listener -> listener.onPlannerModelCallCompleted(result));
	}

	@Override
	public void onPlannerExecutionFailed(PlannerExecutionResult result) {
		listeners.forEach(listener -> listener.onPlannerExecutionFailed(result));
	}

	@Override
	public void onPlannerExecutionApplied(PlannerExecutionResult result) {
		listeners.forEach(listener -> listener.onPlannerExecutionApplied(result));
	}

	@Override
	public void onPlannerExecutionDiscarded(PlannerExecutionResult result) {
		listeners.forEach(listener -> listener.onPlannerExecutionDiscarded(result));
	}

	@Override
	public void onToolRequested(long generation, PlannerToolCall toolCall) {
		listeners.forEach(listener -> listener.onToolRequested(generation, toolCall));
	}

	@Override
	public void onToolExchange(PlannerToolCall call, String result, boolean imageAttached) {
		listeners.forEach(listener -> listener.onToolExchange(call, result, imageAttached));
	}

	@Override
	public void onObservationCompacted(PlannerToolCall call, String finding) {
		listeners.forEach(listener -> listener.onObservationCompacted(call, finding));
	}

	@Override
	public void onToolCompleted(long generation, String toolResult, boolean imageAttached) {
		listeners.forEach(listener -> listener.onToolCompleted(generation, toolResult, imageAttached));
	}

	@Override
	public void onCompactionCompleted(CompactionExecutionResult result) {
		listeners.forEach(listener -> listener.onCompactionCompleted(result));
	}

	@Override
	public void onReset(String reason) {
		listeners.forEach(listener -> listener.onReset(reason));
	}
}
