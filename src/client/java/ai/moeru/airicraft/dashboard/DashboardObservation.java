package ai.moeru.airicraft.dashboard;

import java.util.Objects;

public record DashboardObservation(
	long sequence,
	String sessionId,
	long tick,
	long serverTickId,
	long throughServerTickId,
	long capturedAtMs,
	String type,
	String payloadJson
) {
	public DashboardObservation(long sequence, String sessionId, long tick, long capturedAtMs, String type, String payloadJson) {
		this(sequence, sessionId, tick, tick, tick, capturedAtMs, type, payloadJson);
	}

	public DashboardObservation {
		sessionId = Objects.requireNonNull(sessionId, "sessionId");
		type = Objects.requireNonNull(type, "type");
		payloadJson = Objects.requireNonNull(payloadJson, "payloadJson");
	}

	public long retainedBytes() {
		return (long) payloadJson.length() * Character.BYTES
			+ (long) sessionId.length() * Character.BYTES
			+ (long) type.length() * Character.BYTES
			+ 80L;
	}
}
