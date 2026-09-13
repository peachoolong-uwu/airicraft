package ai.moeru.airicraft.agent.recording;

import ai.moeru.airicraft.agent.llm.LlmChatMessage;
import ai.moeru.airicraft.agent.llm.LlmConversation;
import ai.moeru.airicraft.agent.llm.LlmFailureType;
import ai.moeru.airicraft.agent.llm.LlmUsageSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerExecutionResult;
import ai.moeru.airicraft.agent.llm.PlannerIntent;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import ai.moeru.airicraft.agent.llm.PlannerSessionPhase;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerCallJournalTest {
	@Test void roleCallsHaveDistinctIdentitiesAndGlobalOrdering() {
		var journal = journal(new MutableClock(1_000L), new AtomicLong(10L));
		var thinking = journal.forkRole("thinking", "test", "thinker", List::of);
		var conversation = LlmConversation.of(List.of(LlmChatMessage.system("system")));
		for (var owner : List.of(journal, thinking, journal)) {
			owner.onConversationSubmitted(1L, 1, PlannerSessionPhase.PLANNER_REQUEST, null, conversation);
			owner.onPlannerModelCallCompleted(success(1L, 1, PlannerSessionPhase.PLANNER_REQUEST));
		}
		var calls = journal.snapshot();
		assertEquals(List.of("1", "2", "3"), calls.stream().map(PlannerCallRecordV1::sequence).toList());
		assertEquals("planner-generation-thinking:1", calls.get(1).turnId());
		journal.clear();
		assertTrue(journal.snapshot().isEmpty());
	}

	@Test
	void capturesCanonicalRequestAndAcceptedCompletionOnServerTicks() {
		MutableClock clock = new MutableClock(1_000L);
		AtomicLong tick = new AtomicLong(120L);
		PlannerCallJournal journal = journal(clock, tick);
		LlmConversation conversation = LlmConversation.of(List.of(
			LlmChatMessage.system("system"),
			LlmChatMessage.user("first", ai.moeru.airicraft.agent.llm.LlmMessageKind.USER_TURN),
			LlmChatMessage.user("second", ai.moeru.airicraft.agent.llm.LlmMessageKind.USER_TURN)
		));

		journal.onConversationSubmitted(7L, 1, PlannerSessionPhase.PLANNER_REQUEST, null, conversation);
		tick.set(129L);
		clock.setMillis(1_450L);
		PlannerExecutionResult result = success(7L, 1, PlannerSessionPhase.PLANNER_REQUEST);
		journal.onPlannerModelCallCompleted(result);
		tick.set(130L);
		journal.onPlannerExecutionApplied(result);

		PlannerCallRecordV1 record = journal.snapshot().getFirst();
		assertEquals("planner-call-0001", record.callId());
		assertEquals("planner-generation-7", record.turnId());
		assertEquals("120", record.timeline().submitted().serverTick());
		assertEquals("129", record.timeline().completed().serverTick());
		assertEquals("130", record.timeline().applied().serverTick());
		assertEquals("450", record.timing().latencyMs());
		assertEquals(2, record.request().messages().size());
		assertEquals("first\n\nsecond", record.request().messages().get(1).getAsJsonObject().get("content").getAsString());
		assertEquals("inspect_world", record.request().tools().getFirst().getAsJsonObject()
			.getAsJsonObject("function").get("name").getAsString());
		assertEquals(PlannerCallRecordV1.Status.COMPLETED, record.outcome().status());
		assertEquals(3, record.outcome().usage().totalTokens());
	}

	@Test
	void recordsAPlannerModelFailureWhenTheRawResultReturns() {
		MutableClock clock = new MutableClock(2_000L);
		AtomicLong tick = new AtomicLong(10L);
		PlannerCallJournal journal = journal(clock, tick);
		journal.onConversationSubmitted(
			3L,
			2,
			PlannerSessionPhase.PLANNER_REQUEST,
			null,
			LlmConversation.of(List.of(LlmChatMessage.system("system")))
		);
		PlannerExecutionResult failure = new PlannerExecutionResult(
			null,
			null,
			LlmUsageSnapshot.unknown(),
			LlmFailureType.PROVIDER_ERROR,
			"promotion failed",
			3L,
			2,
			PlannerSessionPhase.PLANNER_REQUEST,
			false
		);
		journal.onPlannerModelCallCompleted(failure);

		PlannerCallRecordV1 record = journal.snapshot().getFirst();
		assertEquals(PlannerCallRecordV1.Status.FAILED, record.outcome().status());
		assertEquals("PROVIDER_ERROR", record.outcome().failure().type());
		assertNull(record.timeline().applied());
	}

	@Test
	void recordsPlannerOffAsDiscardedCompletionWithoutAnAppliedTick() {
		MutableClock clock = new MutableClock(2_500L);
		AtomicLong tick = new AtomicLong(20L);
		PlannerCallJournal journal = journal(clock, tick);
		journal.onConversationSubmitted(
			4L,
			1,
			PlannerSessionPhase.PLANNER_REQUEST,
			null,
			LlmConversation.of(List.of(LlmChatMessage.user("discard me", ai.moeru.airicraft.agent.llm.LlmMessageKind.USER_TURN)))
		);
		PlannerExecutionResult discarded = PlannerExecutionResult.discarded(
			null,
			4L,
			1,
			PlannerSessionPhase.PLANNER_REQUEST
		);

		journal.onPlannerModelCallCompleted(discarded);
		journal.onPlannerExecutionDiscarded(discarded);

		PlannerCallRecordV1 record = journal.snapshot().getFirst();
		assertEquals(PlannerCallRecordV1.Status.COMPLETED, record.outcome().status());
		assertEquals("PLANNER OFF", record.outcome().assistantContent().getAsString());
		assertNull(record.timeline().applied());
	}

	@Test
	void cancelsAnUnfinishedCallOnResetAndCanClearForTheNextEvaluation() {
		MutableClock clock = new MutableClock(3_000L);
		AtomicLong tick = new AtomicLong(40L);
		PlannerCallJournal journal = journal(clock, tick);
		journal.onConversationSubmitted(
			1L,
			1,
			PlannerSessionPhase.PLANNER_REQUEST,
			null,
			LlmConversation.of(List.of(LlmChatMessage.system("system")))
		);
		tick.set(41L);
		journal.onReset("evaluation finished");

		PlannerCallRecordV1 record = journal.snapshot().getFirst();
		assertEquals(PlannerCallRecordV1.Status.CANCELLED, record.outcome().status());
		assertEquals("RESET", record.outcome().failure().type());
		journal.clear();
		assertTrue(journal.snapshot().isEmpty());
	}

	@Test
	void retainsRepeatedToolFollowUpCallsWithTheSameAttemptCoordinates() {
		MutableClock clock = new MutableClock(4_000L);
		AtomicLong tick = new AtomicLong(50L);
		PlannerCallJournal journal = journal(clock, tick);
		LlmConversation conversation = LlmConversation.of(List.of(LlmChatMessage.system("system")));

		for (int index = 0; index < 3; index++) {
			journal.onConversationSubmitted(9L, 1, PlannerSessionPhase.TOOL_FOLLOW_UP, null, conversation);
			tick.incrementAndGet();
			PlannerExecutionResult result = success(9L, 1, PlannerSessionPhase.TOOL_FOLLOW_UP);
			journal.onPlannerModelCallCompleted(result);
			journal.onPlannerExecutionApplied(result);
		}

		assertEquals(List.of("1", "2", "3"), journal.snapshot().stream().map(PlannerCallRecordV1::sequence).toList());
	}

	@Test
	void ignoresCallsWhenNoIntegratedServerTickIsAvailable() {
		PlannerCallJournal journal = journal(new MutableClock(1L), new AtomicLong(-1L));

		journal.onConversationSubmitted(
			1L,
			1,
			PlannerSessionPhase.PLANNER_REQUEST,
			null,
			LlmConversation.of(List.of(LlmChatMessage.system("system")))
		);

		assertTrue(journal.snapshot().isEmpty());
	}

	@Test
	void marksPendingCallsWithTheEvaluationTerminalReason() {
		PlannerCallJournal journal = journal(new MutableClock(5_000L), new AtomicLong(70L));
		journal.onConversationSubmitted(
			2L,
			1,
			PlannerSessionPhase.PLANNER_REQUEST,
			null,
			LlmConversation.of(List.of(LlmChatMessage.system("system")))
		);

		journal.finalizeForEvaluation();

		assertEquals("EVALUATION_TERMINAL", journal.snapshot().getFirst().outcome().failure().type());
	}

	private static PlannerCallJournal journal(Clock clock, AtomicLong tick) {
		return new PlannerCallJournal(
			clock,
			tick::get,
			"openai-compatible",
			"planner-model",
			() -> List.of(Map.of(
				"type", "function",
				"function", Map.of("name", "inspect_world", "parameters", Map.of("type", "object"))
			))
		);
	}

	private static PlannerExecutionResult success(long generation, int attempt, PlannerSessionPhase phase) {
		return new PlannerExecutionResult(
			null,
			new PlannerResponse("ok", new PlannerIntent("reply_only", null, null)),
			new LlmUsageSnapshot(1, 2, 3),
			null,
			null,
			generation,
			attempt,
			phase,
			false
		);
	}

	private static final class MutableClock extends Clock {
		private long millis;

		private MutableClock(long millis) {
			this.millis = millis;
		}

		private void setMillis(long millis) {
			this.millis = millis;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return Instant.ofEpochMilli(millis);
		}
	}
}
