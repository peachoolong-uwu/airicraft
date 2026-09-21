package ai.moeru.airicraft.agent.work;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record WorkSnapshot(WorkHandle handle, String parentWorkId, State state, String label, String phase,
	boolean foreground, long updatedTick, Map<String, Object> details) {
	public enum State {
		QUEUED, RUNNING, WAITING, PAUSED, SUCCEEDED, FAILED, CANCELLED;
		public boolean terminal() { return this == SUCCEEDED || this == FAILED || this == CANCELLED; }
	}
	public WorkSnapshot {
		Objects.requireNonNull(handle);
		Objects.requireNonNull(state);
		parentWorkId = Objects.requireNonNullElse(parentWorkId, "");
		label = Objects.requireNonNullElse(label, "");
		phase = Objects.requireNonNullElse(phase, "");
		details = Map.copyOf(details);
	}
	public Map<String, Object> payload() {
		return Map.of("workId", handle.id(), "parentWorkId", parentWorkId, "state", state, "label", label,
			"phase", phase, "foreground", foreground, "updatedTick", updatedTick, "details", details,
			"controls", state.terminal() ? List.of("inspect") : !parentWorkId.isBlank() ? List.of("inspect") : state == State.PAUSED
				? List.of("inspect", "cancel", "resume") : List.of("inspect", "cancel"));
	}

	/** Small planner projection; inspect_work retains the complete request and evidence. */
	public Map<String, Object> summary() { return summarize(payload()); }

	public static Map<String, Object> summarize(Map<?, ?> payload) {
		var result = new java.util.LinkedHashMap<String, Object>();
		for (String key : List.of("workId", "parentWorkId", "state", "label", "phase", "updatedTick")) {
			Object value = payload.get(key);
			if (value != null && !value.toString().isBlank()) result.put(key, value);
		}
		if (payload.get("details") instanceof Map<?, ?> details) {
			for (String key : List.of("holdId", "failure", "blockedReason", "message", "physicalEffect", "reason", "collected")) {
				Object value = details.get(key);
				if (value != null && !value.toString().isBlank()) result.put(key, value);
			}
		}
		return result;
	}
}
