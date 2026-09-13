package ai.moeru.airicraft.agent.work;

import java.util.Objects;

/** Opaque public identity; the native executor retains ownership of execution. */
public record WorkHandle(String id) {
	public enum Kind { JOB, GRAPH, SMELTING, OPERATION }
	public WorkHandle {
		Objects.requireNonNull(id);
		int separator = id.indexOf(':');
		if (separator < 1 || separator == id.length() - 1) throw new IllegalArgumentException("invalid_work_id");
		Kind.valueOf(id.substring(0, separator));
	}
	public static WorkHandle of(Kind kind, String nativeId) {
		if (nativeId == null || nativeId.isBlank()) throw new IllegalArgumentException("missing_native_work_id");
		return new WorkHandle(kind.name() + ":" + nativeId);
	}
	public Kind kind() { return Kind.valueOf(id.substring(0, id.indexOf(':'))); }
	public String nativeId() { return id.substring(id.indexOf(':') + 1); }
}
