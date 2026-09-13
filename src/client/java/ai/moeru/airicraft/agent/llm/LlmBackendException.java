package ai.moeru.airicraft.agent.llm;

public final class LlmBackendException extends Exception {
	private final LlmFailureType failureType;
	private final long retryAfterMillis;

	public LlmBackendException(LlmFailureType failureType, String message) {
		this(failureType, message, null, 0L);
	}

	public LlmBackendException(LlmFailureType failureType, String message, Throwable cause) {
		this(failureType, message, cause, 0L);
	}

	public LlmBackendException(LlmFailureType failureType, String message, Throwable cause, long retryAfterMillis) {
		super(message, cause);
		this.failureType = failureType;
		this.retryAfterMillis = retryAfterMillis;
	}

	public long retryAfterMillis() { return retryAfterMillis; }

	public LlmFailureType failureType() {
		return failureType;
	}
}
