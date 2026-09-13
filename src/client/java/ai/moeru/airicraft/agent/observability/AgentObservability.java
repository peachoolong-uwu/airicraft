package ai.moeru.airicraft.agent.observability;

import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.llm.LlmConversation;
import ai.moeru.airicraft.agent.llm.LlmUsageSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerRequest;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import ai.moeru.airicraft.agent.llm.CompactionCheckpoint;
import ai.moeru.airicraft.agent.llm.VisionDescription;
import ai.moeru.airicraft.agent.llm.VisionRequest;
import io.opentelemetry.context.Context;

import java.net.URI;

public interface AgentObservability extends AutoCloseable {
	String TURN_SPAN_NAME = "airicraft.planner.turn";
	String PLANNER_REQUEST_SPAN_NAME = "airicraft.planner.request";
	String PLANNER_COMPACTION_SPAN_NAME = "airicraft.planner.compaction";
	String TOOL_CAPTURE_SPAN_NAME = "airicraft.planner.tool.take_a_look.capture";
	String FOLLOW_UP_SPAN_NAME = "airicraft.planner.follow_up";
	String VISION_DESCRIBE_SPAN_NAME = "airicraft.vision.describe";

	static AgentObservability create(AgentConfig.ObservabilityConfig config) {
		return OtelObservability.create(config);
	}

	Context startTurnSpan(PlannerRequest request, String threadId);

	Context startChildSpan(String name, Context parent);

	void setSpanAttribute(Context context, String key, String value);

	void setSpanAttribute(Context context, String key, boolean value);

	void setSpanAttribute(Context context, String key, long value);

	void recordImageCapture(Context context, FirstPersonScreenshotService.CapturedScreenshot capture);

	void recordLlmRequest(
		Context context,
		String providerName,
		URI endpoint,
		String model,
		long timeoutMillis,
		LlmConversation conversation,
		String requestBody
	);

	void recordFailedLlmInput(Context context, LlmConversation conversation, String requestBody);

	void recordLlmRequest(
		Context context,
		String providerName,
		URI endpoint,
		String model,
		long timeoutMillis,
		VisionRequest request,
		String imageDetail,
		String requestBody
	);

	void recordLlmResponse(Context context, Integer statusCode, String responseModel, LlmUsageSnapshot usage, PlannerResponse plannerResponse);

	/** Called on the request thread; the returned listener may run on an HTTP worker. */
	default java.util.function.Consumer<String> streamLlmResponse(Context context) {
		return ignored -> {};
	}

	default void recordRawLlmResponse(Context context, Integer statusCode, String responseModel, LlmUsageSnapshot usage, String rawResponseBody) {
	}

	void recordLlmResponse(Context context, Integer statusCode, String responseModel, LlmUsageSnapshot usage, String rawResponseBody);

	void recordLlmResponse(Context context, Integer statusCode, String responseModel, LlmUsageSnapshot usage, CompactionCheckpoint checkpoint);

	void recordLlmResponse(Context context, Integer statusCode, String responseModel, LlmUsageSnapshot usage, VisionDescription visionDescription);

	void recordFailure(Context context, String failureType, String message, Throwable throwable);

	void endSpan(Context context);

	void shutdown();

	@Override
	default void close() {
		shutdown();
	}
}
