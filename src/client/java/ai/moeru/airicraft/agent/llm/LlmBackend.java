package ai.moeru.airicraft.agent.llm;

public interface LlmBackend {
	LlmCallResult<PlannerResponse> generate(LlmConversation conversation) throws LlmBackendException;

	default LlmCallResult<PlannerResponse> generate(PlannerBackendRequest request) throws LlmBackendException {
		return generate(request.conversation());
	}

	default LlmCallResult<PlannerResponse> generate(PlannerBackendRequest request, java.util.function.Consumer<String> preview) throws LlmBackendException {
		return generate(request);
	}

	void injectMockResponse(PlannerResponse response);

	void injectTimeout();

	boolean isConfigured();

	default boolean managesConversationHistory() {
		return false;
	}

	default boolean supportsGenerationCancellation() {
		return false;
	}

	default void acceptGeneration(long generation) throws LlmBackendException {
	}

	default void discardGeneration(long generation) {
	}

	default void resetBackend() {
	}

	default void shutdownBackend() {
		resetBackend();
	}
}
