package ai.moeru.airicraft.agent.recording;

import ai.moeru.airicraft.agent.llm.LlmConversation;
import ai.moeru.airicraft.agent.llm.LlmUsageSnapshot;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleChatClient;
import ai.moeru.airicraft.agent.llm.PlannerExecutionResult;
import ai.moeru.airicraft.agent.llm.PlannerLifecycleListener;
import ai.moeru.airicraft.agent.llm.PlannerRequest;
import ai.moeru.airicraft.agent.llm.PlannerSessionPhase;
import ai.moeru.airicraft.agent.llm.PlannerToolCatalog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Captures final planner model calls in the producer-owned Play extension
 * contract. The journal has one mutable owner and publishes immutable records.
 */
public final class PlannerCallJournal implements PlannerLifecycleListener {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private final Clock clock;
	private final LongSupplier serverTickSupplier;
	private final PlannerCallRecordV1.Model model;
	private final Supplier<List<Map<String, Object>>> toolsSupplier;
	private final ArrayList<PendingCall> calls = new ArrayList<>();
	private final Map<CallKey, PendingCall> activeCalls = new HashMap<>();
	private java.util.concurrent.atomic.AtomicLong nextSequence = new java.util.concurrent.atomic.AtomicLong(1);
	private final List<PlannerCallJournal> roleJournals = new ArrayList<>();
	private String roleNamespace = "";

	public PlannerCallJournal(
		Clock clock,
		LongSupplier serverTickSupplier,
		String provider,
		String modelName,
		Supplier<List<Map<String, Object>>> toolsSupplier
	) {
		this.clock = Objects.requireNonNull(clock, "clock");
		this.serverTickSupplier = Objects.requireNonNull(serverTickSupplier, "serverTickSupplier");
		this.model = new PlannerCallRecordV1.Model(provider, modelName);
		this.toolsSupplier = Objects.requireNonNull(toolsSupplier, "toolsSupplier");
	}

	@Override
	public synchronized void onConversationSubmitted(
		long generation,
		int attempt,
		PlannerSessionPhase phase,
		PlannerRequest request,
		LlmConversation conversation
	) {
		long serverTick = serverTickSupplier.getAsLong();
		if (serverTick < 0L || generation < 0L || attempt < 1 || phase == null || conversation == null) {
			return;
		}
		CallKey key = new CallKey(generation, attempt, phase);
		long sequence = nextSequence.getAndIncrement();
		PendingCall call = new PendingCall(
			sequence,
			generation,
			attempt,
			phase,
			serverTick,
			clock.millis(),
			jsonObjects(OpenAiCompatibleChatClient.canonicalRequestMessages(conversation)),
			jsonObjects(toolsSupplier.get())
		);
		calls.add(call);
		activeCalls.put(key, call);
	}

	@Override
	public synchronized void onPlannerModelCallCompleted(PlannerExecutionResult result) {
		PendingCall call = find(result);
		if (call == null || result == null) {
			return;
		}
		if (result.succeeded() && result.response() != null) {
			call.complete(
				completedTick(call.submittedTick),
				completedAt(call.requestedAtMs),
				PlannerCallRecordV1.Status.COMPLETED,
				assistantContent(result),
				toolCalls(result),
				usage(result.usage()),
				null
			);
			return;
		}
		String failureType = result.failureType() == null ? "UNKNOWN" : result.failureType().name();
		String failureMessage = result.failureMessage() == null || result.failureMessage().isBlank()
			? "Planner call failed"
			: result.failureMessage();
		call.complete(
			completedTick(call.submittedTick),
			completedAt(call.requestedAtMs),
			PlannerCallRecordV1.Status.FAILED,
			null,
			List.of(),
			usage(result.usage()),
			new PlannerCallRecordV1.Failure(failureType, failureMessage)
		);
	}

	@Override
	public synchronized void onPlannerExecutionApplied(PlannerExecutionResult result) {
		PendingCall call = find(result);
		if (call == null || call.status != PlannerCallRecordV1.Status.COMPLETED || call.completedTick == null) {
			return;
		}
		call.appliedTick = Math.max(call.completedTick.longValue(), nonNegativeTick(call.completedTick.longValue()));
		activeCalls.remove(key(result), call);
	}

	@Override
	public synchronized void onPlannerExecutionDiscarded(PlannerExecutionResult result) {
		PendingCall call = find(result);
		if (call != null) {
			activeCalls.remove(key(result), call);
		}
	}

	@Override
	public synchronized void onReset(String reason) {
		cancelPending("RESET", reason == null || reason.isBlank() ? "Planner runtime reset" : reason);
	}

	public synchronized void finalizeForEvaluation() {
		roleJournals.forEach(PlannerCallJournal::finalizeForEvaluation);
		cancelPending("EVALUATION_TERMINAL", "Evaluation reached a terminal result");
	}

	private void cancelPending(String failureType, String message) {
		for (PendingCall call : calls) {
			if (call.status != null) {
				continue;
			}
			call.complete(
				completedTick(call.submittedTick),
				completedAt(call.requestedAtMs),
				PlannerCallRecordV1.Status.CANCELLED,
				null,
				List.of(),
				new PlannerCallRecordV1.Usage(null, null, null),
				new PlannerCallRecordV1.Failure(failureType, message)
			);
		}
		activeCalls.clear();
	}

	public synchronized PlannerCallJournal forkRole(String role, String provider, String modelName, Supplier<List<Map<String, Object>>> tools) {
		var child = new PlannerCallJournal(clock, serverTickSupplier, provider, modelName, tools);
		child.nextSequence = nextSequence;
		child.roleNamespace = role + ":";
		roleJournals.add(child);
		return child;
	}

	public synchronized List<PlannerCallRecordV1> snapshot() {
		var records = new ArrayList<>(calls.stream().filter(PendingCall::isComplete).map(this::toRecord).toList());
		roleJournals.forEach(child -> records.addAll(child.snapshot()));
		records.sort(java.util.Comparator.comparingLong(record -> Long.parseLong(record.sequence())));
		return List.copyOf(records);
	}

	public synchronized void clear() {
		calls.clear();
		activeCalls.clear();
		roleJournals.forEach(PlannerCallJournal::clear);
		nextSequence.set(1L);
	}

	private PendingCall find(PlannerExecutionResult result) {
		if (result == null || result.phase() == null) {
			return null;
		}
		return activeCalls.get(key(result));
	}

	private static CallKey key(PlannerExecutionResult result) {
		return new CallKey(result.generation(), result.attempt(), result.phase());
	}

	private PlannerCallRecordV1 toRecord(PendingCall call) {
		long completedTick = Objects.requireNonNull(call.completedTick, "completedTick");
		long completedAtMs = Objects.requireNonNull(call.completedAtMs, "completedAtMs");
		return new PlannerCallRecordV1(
			PlannerCallRecordV1.SCHEMA_VERSION,
			"planner-call-%04d".formatted(call.sequence),
			Long.toString(call.sequence),
			"planner-generation-" + roleNamespace + call.generation,
			new PlannerCallRecordV1.PlannerAttempt(Long.toString(call.generation), call.attempt, call.phase.name()),
			new PlannerCallRecordV1.Timeline(
				new PlannerCallRecordV1.Anchor(Long.toString(call.submittedTick)),
				new PlannerCallRecordV1.Anchor(Long.toString(completedTick)),
				call.appliedTick == null ? null : new PlannerCallRecordV1.Anchor(Long.toString(call.appliedTick))
			),
			new PlannerCallRecordV1.Timing(
				Long.toString(call.requestedAtMs),
				Long.toString(completedAtMs),
				Long.toString(completedAtMs - call.requestedAtMs)
			),
			model,
			new PlannerCallRecordV1.Request(call.messages, call.tools),
			new PlannerCallRecordV1.Outcome(call.status, call.assistantContent, call.toolCalls, call.usage, call.failure)
		);
	}

	private long completedTick(long submittedTick) {
		return Math.max(submittedTick, nonNegativeTick(submittedTick));
	}

	private long nonNegativeTick(long fallback) {
		long tick = serverTickSupplier.getAsLong();
		return tick < 0L ? fallback : tick;
	}

	private long completedAt(long requestedAtMs) {
		return Math.max(requestedAtMs, clock.millis());
	}

	private static JsonElement assistantContent(PlannerExecutionResult result) {
		JsonElement raw = result.response().rawAssistantContent();
		return raw == null ? GSON.toJsonTree(result.response()) : raw.deepCopy();
	}

	private static List<JsonElement> toolCalls(PlannerExecutionResult result) {
		return PlannerToolCatalog.toOpenAiToolCalls(result.response().toolCalls()).asList().stream()
			.map(JsonElement::deepCopy)
			.toList();
	}

	private static PlannerCallRecordV1.Usage usage(LlmUsageSnapshot usage) {
		LlmUsageSnapshot effective = usage == null ? LlmUsageSnapshot.unknown() : usage;
		return new PlannerCallRecordV1.Usage(effective.promptTokens(), effective.completionTokens(), effective.totalTokens());
	}

	private static List<JsonElement> jsonObjects(List<Map<String, Object>> values) {
		if (values == null) {
			return List.of();
		}
		ArrayList<JsonElement> result = new ArrayList<>(values.size());
		for (Map<String, Object> value : values) {
			result.add(GSON.toJsonTree(Objects.requireNonNull(value, "JSON object")));
		}
		return List.copyOf(result);
	}

	private record CallKey(long generation, int attempt, PlannerSessionPhase phase) {
	}

	private static final class PendingCall {
		private final long sequence;
		private final long generation;
		private final int attempt;
		private final PlannerSessionPhase phase;
		private final long submittedTick;
		private final long requestedAtMs;
		private final List<JsonElement> messages;
		private final List<JsonElement> tools;
		private Long completedTick;
		private Long appliedTick;
		private Long completedAtMs;
		private PlannerCallRecordV1.Status status;
		private JsonElement assistantContent;
		private List<JsonElement> toolCalls = List.of();
		private PlannerCallRecordV1.Usage usage = new PlannerCallRecordV1.Usage(null, null, null);
		private PlannerCallRecordV1.Failure failure;

		private PendingCall(
			long sequence,
			long generation,
			int attempt,
			PlannerSessionPhase phase,
			long submittedTick,
			long requestedAtMs,
			List<JsonElement> messages,
			List<JsonElement> tools
		) {
			this.sequence = sequence;
			this.generation = generation;
			this.attempt = attempt;
			this.phase = phase;
			this.submittedTick = submittedTick;
			this.requestedAtMs = requestedAtMs;
			this.messages = messages;
			this.tools = tools;
		}

		private void complete(
			long completedTick,
			long completedAtMs,
			PlannerCallRecordV1.Status status,
			JsonElement assistantContent,
			List<JsonElement> toolCalls,
			PlannerCallRecordV1.Usage usage,
			PlannerCallRecordV1.Failure failure
		) {
			this.completedTick = completedTick;
			this.completedAtMs = completedAtMs;
			this.status = status;
			this.assistantContent = assistantContent == null ? null : assistantContent.deepCopy();
			this.toolCalls = List.copyOf(toolCalls);
			this.usage = usage;
			this.failure = failure;
			if (status != PlannerCallRecordV1.Status.COMPLETED) {
				this.appliedTick = null;
			}
		}

		private boolean isComplete() {
			return status != null;
		}
	}
}
