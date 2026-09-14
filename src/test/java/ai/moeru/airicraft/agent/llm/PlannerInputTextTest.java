package ai.moeru.airicraft.agent.llm;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlannerInputTextTest {
	@Test void realRecordedFollowupsAreShorterWithoutDroppingTemporalIdentityOrFailures() throws Exception {
		var input = getClass().getResourceAsStream("/planner/semantic-followups.json");
		var samples = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray();
		int before = 0, after = 0;
		for (var sample : samples) for (var message : sample.getAsJsonObject().getAsJsonArray("messages")) {
			var m = message.getAsJsonObject();
			String raw = m.get("content").getAsString();
			String prose = PlannerInputText.message(m.get("role").getAsString(), raw);
			before += raw.length(); after += prose.length();
			if (raw.startsWith("DECISION CONTEXT: ")) {
				var context = JsonParser.parseString(raw.substring("DECISION CONTEXT: ".length())).getAsJsonObject();
				assertTrue(prose.contains("Evidence after " + context.get("afterEventSequence") + " through " + context.get("throughEventSequence")));
				assertTrue(prose.contains(context.get("worldSessionId").getAsString()));
				assertTrue(prose.contains(context.getAsJsonObject("current").getAsJsonObject("objective").get("objective").getAsString()));
				for (var event : context.getAsJsonArray("events")) {
					var e = event.getAsJsonObject();
					assertTrue(prose.contains("Event " + e.get("seqNo") + " at tick " + e.get("tick") + ": " + e.get("type").getAsString()));
				}
			}
		}
		System.out.println("Recorded followups characters: " + before + " -> " + after);
		assertTrue(after < before * .9, before + " -> " + after);
	}

	@Test void receiptKeepsRejectionHoldAndUnknownExtensionFields() {
		String raw = "Tool result for cancel_work: {\"accepted\":false,\"workId\":\"JOB:one\",\"state\":\"PAUSED\",\"phase\":\"WAITING_RELEASE\",\"holdId\":\"hold1\",\"failure\":{\"predicate\":\"outside_bounds\",\"x\":3},\"futureFlag\":false}";
		String prose = PlannerInputText.message("tool", raw);
		for (String fact : java.util.List.of("Rejected", "JOB:one", "PAUSED", "WAITING_RELEASE", "hold1", "outside_bounds", "\"x\":3", "\"futureFlag\":false")) assertTrue(prose.contains(fact), prose);
		assertEquals(raw, PlannerInputText.message("assistant", raw));
		assertEquals(raw, PlannerInputText.message("user", raw));
		assertEquals("Tool result for custom: {\"unfamiliar\":true}", PlannerInputText.message("tool", "Tool result for custom: {\"unfamiliar\":true}"));
	}

	@Test void missingRangesAndUnknownFieldsPassThroughAndInputDoesNotMutate() {
		var payload = Map.<String, Object>of("worldSessionId", "world", "tick", 10, "serverTick", 8, "decisionOwner", "thinker", "actuatorOwner", "reflex",
			"missingEventRange", Map.of("from", 2, "to", 4), "current", Map.of("physical", Map.of("grounded", false, "touchingWater", true, "climbing", false, "newFlag", true), "newFact", Map.of("a", 7)),
			"future", Map.of("opaque", "minecraft:leave_native_identifier"));
		String original = new Gson().toJson(payload);
		String prose = PlannerInputText.decision(payload);
		for (String fact : java.util.List.of("thinker", "reflex", "MISSING evidence", "\"from\":2", "\"to\":4", "off ground", "touching water", "not climbing", "\"newFlag\":true", "\"newFact\"", "leave_native_identifier")) assertTrue(prose.contains(fact), prose);
		assertEquals(original, new Gson().toJson(payload));
	}

	@Test void modelBoundaryRetainsPairingAndNativeCanonicalHistory() {
		var raw = "Tool result for use_block: {\"accepted\":true,\"workId\":\"JOB:12345678-1234-1234-1234-123456789abc\",\"state\":\"SUCCEEDED\",\"phase\":\"SUCCEEDED\"}";
		var messages = java.util.List.of(Map.<String, Object>of("role", "tool", "tool_call_id", "protocol-id", "content", raw));
		var wire = new PlannerReferences().presentMessages(messages).get(0).getAsJsonObject();
		assertEquals("protocol-id", wire.get("tool_call_id").getAsString());
		assertTrue(wire.get("content").getAsString().contains("Accepted; Work @r"));
		assertTrue(wire.get("content").getAsString().contains("state and phase SUCCEEDED"));
		assertEquals(raw, messages.getFirst().get("content"));
	}
	@Test void eventAliasesOnlyAnIdenticalSnapshotWithinThisRequest() {
		var work = Map.of("workId", "JOB:one", "state", "RUNNING", "updatedTick", 9, "message", "approaching target");
		var different = Map.of("workId", "JOB:one", "state", "FAILED", "updatedTick", 8, "message", "blocked");
		var payload = Map.<String,Object>of("current", Map.of("work", java.util.List.of(work)), "events", java.util.List.of(
			Map.of("seqNo", 2, "tick", 8, "type", "work.changed", "payload", different),
			Map.of("seqNo", 3, "tick", 9, "type", "work.changed", "payload", work)));
		String prose = PlannerInputText.decision(payload);
		assertTrue(prose.contains("Event 3 at tick 9: work.changed: same snapshot as current work JOB:one."));
		assertTrue(prose.contains("state FAILED"));
		assertTrue(prose.contains("blocked"));
		assertEquals(1, prose.split("approaching target", -1).length - 1);
	}

	@Test void goalContinuationWrapperDoesNotHideItsDecisionContext() {
		String context = "DECISION CONTEXT: {\"worldSessionId\":\"world\",\"tick\":9,\"serverTick\":7,\"decisionOwner\":\"controller\",\"actuatorOwner\":\"idle\",\"current\":{\"inventory\":{\"minecraft:dirt\":4}},\"afterEventSequence\":2,\"throughEventSequence\":3,\"events\":[]}";
		String prefix = "Current planner goal (stored intent): preserve this exact constraint.\n\nGOAL CONTINUATION: continue.";
		String wrapped = prefix + "\n\n" + context;
		String prose = PlannerInputText.message("user", wrapped);
		assertTrue(prose.startsWith(prefix + "\n\nDECISION CONTEXT:\n"));
		assertTrue(prose.contains("Carrying 4 dirt."));
		assertTrue(prose.contains("Evidence after 2 through 3."));
		assertFalse(prose.contains("DECISION CONTEXT: {"));
		assertEquals(prose, PlannerInputText.message("user", prose));
		assertEquals(wrapped, PlannerInputText.message("assistant", wrapped));
		assertEquals("Other text\n\nDECISION CONTEXT: {incomplete", PlannerInputText.message("user", "Other text\n\nDECISION CONTEXT: {incomplete"));
	}

	@Test void debugPanelSharesModelReferencesAndLeavesCanonicalMessagesIntact() {
		String id = "JOB:job-11111111-2222-3333-4444-555555555555";
		String nativeText = "Tool result for inspect_work: {\"workId\":\"" + id + "\",\"state\":\"RUNNING\"}";
		var message = new PlannerConversationDebugMessage("tool", PlannerConversationDebugKind.TOOL_RESULT, nativeText, 3, "TOOL_EXECUTION", 1, false);
		var canonical = new PlannerConversationDebugSnapshot(3, "TOOL_EXECUTION", 1, java.util.List.of(message));
		var references = new PlannerReferences();
		String modelRef = references.present(id);
		var display = canonical.presented(references);
		assertTrue(display.messages().getFirst().text().contains("Work " + modelRef));
		assertFalse(display.messages().getFirst().text().contains(id));
		assertEquals(display, canonical.presented(references));
		assertEquals(nativeText, canonical.messages().getFirst().text());
		assertEquals(3, display.generation());
		assertEquals(message.kind(), display.messages().getFirst().kind());
	}

}
