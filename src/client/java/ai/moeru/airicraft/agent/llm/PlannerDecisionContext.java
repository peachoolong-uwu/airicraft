package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.events.SemanticEventQueryResult;
import com.google.gson.GsonBuilder;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable game-thread observation. Evidence exists independently of a planner wakeup. */
public record PlannerDecisionContext(
	String worldSessionId, long tick, long serverTick, String decisionOwner,
	String actuatorOwner, Map<String, Object> current, SemanticEventQueryResult observations
) {
	public PlannerDecisionContext {
		Objects.requireNonNull(worldSessionId);
		Objects.requireNonNull(decisionOwner);
		Objects.requireNonNull(actuatorOwner);
		current = Map.copyOf(current);
		Objects.requireNonNull(observations);
	}

	public PlannerDecisionContext forOwner(String owner) {
		return new PlannerDecisionContext(worldSessionId, tick, serverTick, owner, actuatorOwner, current, observations);
	}

	public LlmChatMessage message(long sinceSequence) {
		List<SemanticEvent> events = observations.events().stream()
			.filter(event -> event.seqNo() > sinceSequence && relevant(event.type())).toList();
		boolean gap = observations.oldestSeqNo() > Math.max(1, sinceSequence + 1);
		var payload = new java.util.LinkedHashMap<String, Object>();
		payload.put("worldSessionId", worldSessionId);
		payload.put("tick", tick);
		payload.put("serverTick", serverTick);
		payload.put("decisionOwner", decisionOwner);
		payload.put("actuatorOwner", actuatorOwner);
		payload.put("current", current);
		payload.put("afterEventSequence", sinceSequence);
		payload.put("throughEventSequence", observations.latestSeqNo());
		if (gap) payload.put("missingEventRange", Map.of("from", sinceSequence + 1, "to", observations.oldestSeqNo() - 1));
		payload.put("events", events);
		return LlmChatMessage.user("DECISION CONTEXT: " + new GsonBuilder().disableHtmlEscaping().create().toJson(payload), LlmMessageKind.NOTICE);
	}

	private static boolean relevant(String type) {
		return List.of("task.", "work.", "player.", "combat.", "pickup.", "crafting.", "smelting.",
			"container.", "interaction.", "objective.", "policy.", "inventory.", "reflex.", "survival.", "session.", "lighting.", "food.").stream().anyMatch(type::startsWith);
	}
}
