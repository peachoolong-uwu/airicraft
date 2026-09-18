package ai.moeru.airicraft.agent.llm;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PlannerSnapshotPresentationTest {
	private static String snapshot(String world, int tick, String current, boolean refresh) {
		return "DECISION CONTEXT: {\"worldSessionId\":\"" + world + "\",\"tick\":" + tick
			+ ",\"serverTick\":" + tick + ",\"current\":" + current + (refresh ? ",\"stateBaseline\":true" : "")
			+ ",\"afterEventSequence\":0,\"throughEventSequence\":0,\"events\":[]}";
	}
	private static JsonObject payload(String message) { return JsonParser.parseString(message.substring("DECISION CONTEXT: ".length())).getAsJsonObject(); }

	@Test void nestedChangesAndRemovalsDoNotRepeatUnchangedNotes() {
		var projection = new PlannerSnapshotPresentation();
		String before = "{\"objective\":{\"decisions\":{\"old\":\"historical note\"}},\"inventory\":{\"oak_log\":2,\"dirt\":1},\"work\":[1]}";
		String after = "{\"objective\":{\"decisions\":{\"old\":\"historical note\"}},\"inventory\":{\"oak_log\":1},\"work\":[]}";
		assertTrue(payload(projection.message("user", snapshot("a", 1, before, false))).has("current"));
		JsonObject delta = payload(projection.message("user", snapshot("a", 2, after, false)));
		assertFalse(delta.has("current"));
		assertEquals(JsonParser.parseString("[{\"remove\":[\"inventory\",\"dirt\"]},{\"set\":[\"inventory\",\"oak_log\"],\"value\":1},{\"set\":[\"work\"],\"value\":[]}]"), delta.get("stateChanges"));
		String unchanged = projection.message("user", snapshot("a", 3, after, false));
		assertFalse(unchanged.contains("historical note"));
		assertFalse(payload(unchanged).has("stateChanges"));
		assertEquals(3, payload(unchanged).get("tick").getAsInt());
	}

	@Test void newWorldRefreshAndLostHistoryAlwaysReceiveFullState() {
		var projection = new PlannerSnapshotPresentation();
		projection.message("user", snapshot("a", 1, "{\"health\":20}", false));
		assertTrue(payload(projection.message("user", snapshot("b", 2, "{\"health\":20}", false))).has("current"));
		assertTrue(payload(projection.message("user", snapshot("b", 3, "{\"health\":20}", true))).has("current"));
		String gap = snapshot("b", 4, "{\"health\":20}", false).replace("\"events\":[]", "\"events\":[],\"missingEventRange\":{\"from\":1,\"to\":5}");
		assertTrue(payload(projection.message("user", gap)).has("current"));
		assertTrue(payload(new PlannerSnapshotPresentation().message("user", snapshot("b", 5, "{\"health\":20}", false))).has("current"));
	}

	@Test void wireRetriesAreIdenticalAndCanonicalSnapshotsStayIntact() {
		var refs = new PlannerReferences();
		String baseline = snapshot("a", 1, "{\"inventory\":{\"dirt\":2}}", false);
		String next = snapshot("a", 2, "{\"inventory\":{\"dirt\":1}}", false);
		var messages = List.<Map<String,Object>>of(Map.of("role", "user", "content", baseline), Map.of("role", "user", "content", next));
		var wire = refs.presentMessages(messages);
		assertEquals(wire, refs.presentMessages(messages));
		assertEquals(next, messages.get(1).get("content"));
		assertTrue(wire.get(1).toString().contains("State changes"));
		assertTrue(refs.presentMessages(List.of(messages.get(1))).toString().contains("Full state baseline"));
	}

	@Test void objectiveEventKeepsIdentityWithoutRepeatingCurrentNotebook() {
		String raw = snapshot("a", 1, "{\"objective\":{\"decisions\":{\"old\":\"long historical note\"}}}", false)
			.replace("\"events\":[]", "\"events\":[{\"seqNo\":8,\"tick\":1,\"type\":\"objective.changed\",\"payload\":{\"objective\":{\"decisions\":{\"old\":\"long historical note\"}},\"reason\":\"updated\"}}]");
		String rendered = new PlannerSnapshotPresentation().message("user", raw);
		assertEquals(1, rendered.split("long historical note", -1).length - 1);
		JsonObject event = payload(rendered).getAsJsonArray("events").get(0).getAsJsonObject();
		assertEquals(8, event.get("seqNo").getAsInt());
		assertEquals("updated", event.getAsJsonObject("payload").get("reason").getAsString());
		assertTrue(event.getAsJsonObject("payload").has("objectiveState"));
	}
}
