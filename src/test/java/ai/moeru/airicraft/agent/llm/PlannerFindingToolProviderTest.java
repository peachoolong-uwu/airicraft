package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlannerFindingToolProviderTest {
	private static final PlannerToolCall QUERY = new PlannerToolCall("query-1", "query_world", new JsonObject(), null, null);
	private static final LlmConversation RAW = LlmConversation.of(List.of(LlmChatMessage.assistantToolCall("", QUERY), LlmChatMessage.tool("query-1", "TOOL_ERROR: wrong area; unloaded chunk")));
	private static PlannerToolCall finding(String json) {
		return new PlannerToolCall("finding-1", "record_finding", JsonParser.parseString(json).getAsJsonObject(), null, null);
	}
	private static final PlannerToolCall FINDING = finding("{\"sourceToolCallId\":\"query-1\",\"result\":null,\"memory\":\"West area unloaded; not evidence of no hole. Try loaded east section.\"}");

	@Test void queuedAcknowledgmentsAreNotObservationsAndFindingCanAccompanyPlan() {
		var queued = LlmConversation.of(List.of(LlmChatMessage.assistantToolCall("", QUERY), LlmChatMessage.tool(QUERY.id(), "QUEUED: awaiting execution")));
		assertNull(PlannerFindingToolProvider.pending(queued));
		var next = new PlannerToolCall("next", "mine_blocks", new JsonObject(), null, null);
		assertNull(PlannerFindingToolProvider.validateQueuedResponse(RAW, List.of(FINDING, next)));
		var batch = RAW.withAppended(LlmChatMessage.assistantToolCalls("", List.of(FINDING, next)))
			.withAppended(LlmChatMessage.tool(FINDING.id(), "accepted")).withAppended(LlmChatMessage.tool(next.id(), "QUEUED: next"));
		var retained = PlannerFindingToolProvider.afterTool(batch, FINDING, "accepted");
		assertTrue(retained.messages().stream().anyMatch(m -> m.toolCalls().contains(next)));
		assertTrue(retained.messages().stream().anyMatch(m -> next.id().equals(m.toolCallId())));
		assertFalse(retained.messages().stream().anyMatch(m -> m.toolCalls().contains(FINDING)));
	}

	@Test void partialFindingBatchCommitsProgressInsteadOfRejectingAllFindings() {
		var second = new PlannerToolCall("query-2", "find_world_features", new JsonObject(), null, null);
		var input = RAW.withAppended(LlmChatMessage.assistantToolCall("", second)).withAppended(LlmChatMessage.tool(second.id(), "No forest east"));
		assertNull(PlannerFindingToolProvider.validateQueuedResponse(input, List.of(FINDING)));
		var committed = PlannerFindingToolProvider.afterTool(input, FINDING, "accepted");
		assertEquals("query-2", PlannerFindingToolProvider.pending(committed).toolCallId());
		assertNotNull(PlannerFindingToolProvider.validateQueuedResponse(input, List.of(FINDING, QUERY)), "New work still requires all visible observations to be summarized");
	}

	@Test void cannotSkipFindingWithAnotherQueryBatchOrFinalReply() {
		assertNotNull(PlannerFindingToolProvider.validateNext(RAW, List.of(QUERY)));
		assertNotNull(PlannerFindingToolProvider.validateNext(RAW, List.of()));
		assertNotNull(PlannerFindingToolProvider.validateNext(RAW, List.of(FINDING, QUERY)));
		assertNull(PlannerFindingToolProvider.validateNext(RAW, List.of(FINDING)));
	}
	@Test void rejectsWrongSourceMissingMemoryAndMissingExplicitResult() {
		for (String args : List.of(
			"{\"sourceToolCallId\":\"wrong\",\"result\":null,\"memory\":\"No hole\"}",
			"{\"sourceToolCallId\":\"query-1\",\"result\":null,\"memory\":\"\"}",
			"{\"sourceToolCallId\":\"query-1\",\"memory\":\"No hole\"}")) {
			assertNotNull(PlannerFindingToolProvider.validateNext(RAW, List.of(finding(args))));
		}
		assertNotNull(PlannerFindingToolProvider.validateNext(LlmConversation.of(List.of()), List.of(FINDING)));
	}
	@Test void negativeFindingReplacesOnlyItsSourceAndKeepsCallPairing() {
		var unrelated = LlmChatMessage.tool("inventory", "3 stone bricks");
		var input = RAW.withAppended(unrelated).withAppended(LlmChatMessage.assistantToolCall("", FINDING)).withAppended(LlmChatMessage.tool(FINDING.id(), "accepted"));
		var output = PlannerFindingToolProvider.afterTool(input, FINDING, "accepted");
		assertEquals(3, output.messages().size());
		assertEquals(RAW.messages().getFirst(), output.messages().getFirst());
		assertEquals("query-1", output.messages().get(1).toolCallId());
		assertTrue(output.messages().get(1).content().contains("West area unloaded"));
		assertTrue(output.messages().get(1).content().contains("\"result\":null"));
		assertSame(unrelated, output.messages().getLast());
		assertNull(PlannerFindingToolProvider.pending(output));
		assertNull(PlannerFindingToolProvider.validateNext(output, List.of(QUERY)));
		assertNotNull(PlannerFindingToolProvider.pending(RAW), "Previous request remains immutable for debug evidence");
	}
	@Test void failedReceiptNeverDropsRawEvidence() {
		var output = PlannerFindingToolProvider.afterTool(RAW, FINDING, "TOOL_ERROR: failure");
		assertEquals(RAW.messages(), output.messages().subList(0, RAW.messages().size()));
		assertNotNull(PlannerFindingToolProvider.pending(output));
	}
}
