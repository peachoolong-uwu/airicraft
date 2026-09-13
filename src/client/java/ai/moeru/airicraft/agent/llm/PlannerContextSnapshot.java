package ai.moeru.airicraft.agent.llm;

import java.util.Objects;

public record PlannerContextSnapshot(
	PlannerRequest request,
	PlannerSnapshotMode mode,
	PlannerTriggerBatch triggerBatch,
	LlmConversation plannerConversation,
	long includedSemanticEventSeqNoUpperBound,
	long includedSemanticGapVersion,
	PlannerAmbientContext renderedAmbientContext,
	long renderedTimeContextAtMs
) {
	public PlannerContextSnapshot withConversation(LlmConversation conversation) {
		return new PlannerContextSnapshot(request, mode, triggerBatch, conversation,
			includedSemanticEventSeqNoUpperBound, includedSemanticGapVersion, renderedAmbientContext, renderedTimeContextAtMs);
	}

	public PlannerContextSnapshot {
		request = Objects.requireNonNull(request, "request");
		mode = Objects.requireNonNull(mode, "mode");
		triggerBatch = Objects.requireNonNullElseGet(triggerBatch, () -> PlannerTriggerBatch.of(java.util.List.of()));
		plannerConversation = Objects.requireNonNull(plannerConversation, "plannerConversation");
		renderedAmbientContext = Objects.requireNonNull(renderedAmbientContext, "renderedAmbientContext");
	}
}
