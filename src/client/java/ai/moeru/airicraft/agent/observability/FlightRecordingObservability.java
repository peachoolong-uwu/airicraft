package ai.moeru.airicraft.agent.observability;

import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.debug.LlmFlightRecorder;
import ai.moeru.airicraft.agent.llm.CompactionCheckpoint;
import ai.moeru.airicraft.agent.llm.LlmConversation;
import ai.moeru.airicraft.agent.llm.LlmUsageSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerRequest;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import ai.moeru.airicraft.agent.llm.VisionDescription;
import ai.moeru.airicraft.agent.llm.VisionRequest;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;

import java.net.URI;
import java.util.Objects;

public final class FlightRecordingObservability implements AgentObservability {
	private static final ContextKey<String> FLIGHT_THREAD_ID = ContextKey.named("airicraft.flight.thread_id");
	private static final ContextKey<String> FLIGHT_REQUEST_KIND = ContextKey.named("airicraft.flight.request_kind");

	private final AgentObservability delegate;
	private final LlmFlightRecorder recorder;

	public FlightRecordingObservability(AgentObservability delegate, LlmFlightRecorder recorder) {
		this.delegate = Objects.requireNonNull(delegate, "delegate");
		this.recorder = Objects.requireNonNull(recorder, "recorder");
	}

	@Override
	public Context startTurnSpan(PlannerRequest request, String threadId) {
		Context context = delegate.startTurnSpan(request, threadId);
		return context.with(FLIGHT_THREAD_ID, threadId == null ? "" : threadId);
	}

	@Override
	public Context startChildSpan(String name, Context parent) {
		Context context = delegate.startChildSpan(name, parent);
		String threadId = contextValue(parent, FLIGHT_THREAD_ID);
		if (!threadId.isBlank()) {
			context = context.with(FLIGHT_THREAD_ID, threadId);
		}
		String requestKind = requestKind(name);
		if (!requestKind.isBlank()) {
			context = context.with(FLIGHT_REQUEST_KIND, requestKind);
		}
		return context;
	}

	@Override
	public void setSpanAttribute(Context context, String key, String value) {
		delegate.setSpanAttribute(context, key, value);
	}

	@Override
	public void setSpanAttribute(Context context, String key, boolean value) {
		delegate.setSpanAttribute(context, key, value);
	}

	@Override
	public void setSpanAttribute(Context context, String key, long value) {
		delegate.setSpanAttribute(context, key, value);
	}

	@Override
	public void recordImageCapture(Context context, FirstPersonScreenshotService.CapturedScreenshot capture) {
		delegate.recordImageCapture(context, capture);
	}

	@Override
	public void recordLlmRequest(
		Context context,
		String providerName,
		URI endpoint,
		String model,
		long timeoutMillis,
		LlmConversation conversation,
		String requestBody
	) {
		delegate.recordLlmRequest(context, providerName, endpoint, model, timeoutMillis, conversation, requestBody);
		recorder.recordRequest(
			contextValue(context, FLIGHT_REQUEST_KIND),
			contextValue(context, FLIGHT_THREAD_ID),
			providerName,
			endpoint,
			model,
			timeoutMillis,
			conversation,
			requestBody
		);
	}

	@Override
	public void recordFailedLlmInput(Context context, LlmConversation conversation, String requestBody) {
		delegate.recordFailedLlmInput(context, conversation, requestBody);
	}

	@Override
	public void recordLlmRequest(
		Context context,
		String providerName,
		URI endpoint,
		String model,
		long timeoutMillis,
		VisionRequest request,
		String imageDetail,
		String requestBody
	) {
		delegate.recordLlmRequest(context, providerName, endpoint, model, timeoutMillis, request, imageDetail, requestBody);
		recorder.recordRequest(
			contextValue(context, FLIGHT_REQUEST_KIND),
			contextValue(context, FLIGHT_THREAD_ID),
			providerName,
			endpoint,
			model,
			timeoutMillis,
			null,
			requestBody
		);
	}

	@Override
	public void recordLlmResponse(Context context, Integer statusCode, String responseModel, LlmUsageSnapshot usage, PlannerResponse plannerResponse) {
		delegate.recordLlmResponse(context, statusCode, responseModel, usage, plannerResponse);
		recorder.recordParsedResponse("planner", statusCode, responseModel, usage, plannerResponse);
	}

	@Override
	public java.util.function.Consumer<String> streamLlmResponse(Context context) {
		return recorder.streamListener().andThen(delegate.streamLlmResponse(context));
	}

	@Override
	public void recordRawLlmResponse(Context context, Integer statusCode, String responseModel, LlmUsageSnapshot usage, String rawResponseBody) {
		delegate.recordRawLlmResponse(context, statusCode, responseModel, usage, rawResponseBody);
		recorder.recordRawResponse(statusCode, responseModel, usage, rawResponseBody);
	}

	@Override
	public void recordLlmResponse(Context context, Integer statusCode, String responseModel, LlmUsageSnapshot usage, String rawResponseBody) {
		delegate.recordLlmResponse(context, statusCode, responseModel, usage, rawResponseBody);
		recorder.recordRawResponse(statusCode, responseModel, usage, rawResponseBody);
	}

	@Override
	public void recordLlmResponse(Context context, Integer statusCode, String responseModel, LlmUsageSnapshot usage, CompactionCheckpoint checkpoint) {
		delegate.recordLlmResponse(context, statusCode, responseModel, usage, checkpoint);
		recorder.recordParsedResponse("compaction", statusCode, responseModel, usage, checkpoint);
	}

	@Override
	public void recordLlmResponse(Context context, Integer statusCode, String responseModel, LlmUsageSnapshot usage, VisionDescription visionDescription) {
		delegate.recordLlmResponse(context, statusCode, responseModel, usage, visionDescription);
		recorder.recordParsedResponse("vision", statusCode, responseModel, usage, visionDescription);
	}

	@Override
	public void recordFailure(Context context, String failureType, String message, Throwable throwable) {
		delegate.recordFailure(context, failureType, message, throwable);
		recorder.recordFailure(failureType, message);
	}

	@Override
	public void endSpan(Context context) {
		delegate.endSpan(context);
	}

	@Override
	public void shutdown() {
		delegate.shutdown();
	}

	private static String contextValue(Context context, ContextKey<String> key) {
		String value = context == null ? null : context.get(key);
		return value == null ? "" : value;
	}

	private static String requestKind(String spanName) {
		return switch (spanName) {
			case PLANNER_REQUEST_SPAN_NAME -> "planner";
			case PLANNER_COMPACTION_SPAN_NAME -> "compaction";
			case "planner.micro_compaction" -> "micro_compaction";
			case FOLLOW_UP_SPAN_NAME -> "follow_up";
			case VISION_DESCRIBE_SPAN_NAME -> "vision";
			default -> "";
		};
	}
}
