package ai.moeru.airicraft.agent.observability;

import ai.moeru.airicraft.agent.debug.LlmFlightRecord;
import ai.moeru.airicraft.agent.debug.LlmFlightRecordQueryResult;
import ai.moeru.airicraft.agent.debug.LlmFlightRecorder;
import ai.moeru.airicraft.agent.llm.LlmChatMessage;
import ai.moeru.airicraft.agent.llm.LlmConversation;
import ai.moeru.airicraft.agent.llm.LlmMessageKind;
import ai.moeru.airicraft.agent.llm.LlmUsageSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerIntent;
import ai.moeru.airicraft.agent.llm.PlannerRequest;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import ai.moeru.airicraft.agent.session.SessionMode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlightRecordingObservabilityTest {
	@Test
	void labelsMicroCompactionRequestsForInspection() {
		var recorder = new LlmFlightRecorder();
		var observability = new FlightRecordingObservability(NoopObservability.INSTANCE, recorder);
		var context = observability.startChildSpan("planner.micro_compaction", Context.root());
		observability.recordLlmRequest(context, "provider", URI.create("http://localhost/test"),
			"model", 1000L, LlmConversation.of(List.of(LlmChatMessage.system("compact"))), "{}");
		assertEquals("micro_compaction", recorder.query(null).records().getFirst().requestKind());
	}

	@Test
	void recordsExactPlannerRequestRawResponseAndParsedOutput() {
		LlmFlightRecorder recorder = new LlmFlightRecorder();
		AgentObservability observability = new FlightRecordingObservability(NoopObservability.INSTANCE, recorder);
		PlannerRequest request = new PlannerRequest(
			1L,
			1000L,
			SessionMode.SINGLEPLAYER_LOCAL,
			null,
			null,
			"Player",
			"make a pick",
			null
		);
		Context turn = observability.startTurnSpan(request, "thread-1");
		Context planner = observability.startChildSpan(AgentObservability.PLANNER_REQUEST_SPAN_NAME, turn);

		try (Scope ignored = planner.makeCurrent()) {
			observability.recordLlmRequest(
				Context.current(),
				"test-provider",
				URI.create("http://127.0.0.1:8080/chat/completions"),
				"planner-model",
				1234L,
				LlmConversation.of(List.of(
					LlmChatMessage.system("system prompt"),
					LlmChatMessage.user("latest task", LlmMessageKind.USER_TURN)
				)),
				"{\"model\":\"planner-model\",\"messages\":[{\"role\":\"system\",\"content\":\"system prompt\"}]}"
			);
			observability.recordRawLlmResponse(
				Context.current(),
				200,
				"planner-model",
				new LlmUsageSnapshot(11, 7, 18),
				"{\"choices\":[{\"message\":{\"content\":\"done\"}}]}"
			);
			observability.recordLlmResponse(
				Context.current(),
				200,
				"planner-model",
				new LlmUsageSnapshot(11, 7, 18),
				new PlannerResponse("done", new PlannerIntent("reply_only", null, null))
			);
		}

		LlmFlightRecordQueryResult result = recorder.query(null);
		assertFalse(result.truncated());
		assertEquals(1, result.records().size());
		LlmFlightRecord record = result.records().getFirst();
		assertEquals("COMPLETED", record.status());
		assertEquals("planner", record.requestKind());
		assertEquals("thread-1", record.threadId());
		assertEquals("test-provider", record.providerName());
		assertEquals("planner-model", record.model());
		assertEquals(2, record.messageCount());
		assertEquals("{\"model\":\"planner-model\",\"messages\":[{\"role\":\"system\",\"content\":\"system prompt\"}]}", record.requestBody());
		assertEquals("{\"choices\":[{\"message\":{\"content\":\"done\"}}]}", record.rawResponseBody());
		assertEquals("planner", record.parsedResponseKind());
		PlannerResponse response = assertInstanceOf(PlannerResponse.class, record.parsedResponse());
		assertEquals("done", response.replyText());
		assertEquals(Integer.valueOf(11), record.usage().promptTokens());
	}

	@Test
	void queryReportsTruncationWhenCapacityDropsOldRecords() {
		LlmFlightRecorder recorder = new LlmFlightRecorder(2);
		for (int index = 0; index < 3; index++) {
			recorder.recordRequest(
				"planner",
				"thread",
				"provider",
				URI.create("http://127.0.0.1/" + index),
				"model",
				1000L,
				LlmConversation.of(List.of(LlmChatMessage.user("message " + index, LlmMessageKind.USER_TURN))),
				"{\"index\":" + index + "}"
			);
		}

		LlmFlightRecordQueryResult result = recorder.query(null);
		assertTrue(result.truncated());
		assertEquals(2, result.records().size());
		assertEquals(2L, result.oldestSequenceId());
		assertEquals(3L, result.latestSequenceId());
	}
}
