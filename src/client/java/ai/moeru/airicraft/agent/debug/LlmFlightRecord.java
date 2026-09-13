package ai.moeru.airicraft.agent.debug;

import ai.moeru.airicraft.agent.llm.LlmUsageSnapshot;

public record LlmFlightRecord(
	long sequenceId,
	long requestedAtMs,
	long completedAtMs,
	String status,
	String requestKind,
	String threadId,
	long javaThreadId,
	String providerName,
	String endpoint,
	String model,
	long timeoutMillis,
	int messageCount,
	boolean imageAttached,
	String requestBody,
	Integer statusCode,
	String responseModel,
	LlmUsageSnapshot usage,
	String rawResponseBody,
	String parsedResponseKind,
	Object parsedResponse,
	String failureType,
	String failureMessage,
	long dispatchTick,
	long dispatchServerTick,
	java.util.Map<String, Object> decisionContext
) {
	public LlmFlightRecord {
		status = status == null || status.isBlank() ? "REQUESTED" : status;
		requestKind = requestKind == null ? "" : requestKind;
		threadId = threadId == null ? "" : threadId;
		providerName = providerName == null ? "" : providerName;
		endpoint = endpoint == null ? "" : endpoint;
		model = model == null ? "" : model;
		requestBody = requestBody == null ? "" : requestBody;
		usage = usage == null ? LlmUsageSnapshot.unknown() : usage;
		rawResponseBody = rawResponseBody == null ? "" : rawResponseBody;
		parsedResponseKind = parsedResponseKind == null ? "" : parsedResponseKind;
		failureType = failureType == null ? "" : failureType;
		failureMessage = failureMessage == null ? "" : failureMessage;
	}
}
