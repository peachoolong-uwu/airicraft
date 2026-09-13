package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import com.google.gson.JsonParser;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlannerDecisionContextTest {
	@Test void overflowReportsMissingRangeAlongsideCurrentFactsAndRetainedOutcomes() {
		var events = new SemanticEventBuffer(2);
		events.append(1, "task.started", Map.of("workId", "old"));
		events.append(2, "task.failed", Map.of("workId", "old"));
		events.append(3, "task.started", Map.of("workId", "new"));
		var context = new PlannerDecisionContext("world", 3, 3, "controller", "work",
			Map.of("workId", "new"), events.query(null));
		var payload = JsonParser.parseString(context.message(0).content().substring("DECISION CONTEXT: ".length())).getAsJsonObject();
		assertEquals(1, payload.getAsJsonObject("missingEventRange").get("to").getAsLong());
		assertEquals("new", payload.getAsJsonObject("current").get("workId").getAsString());
		assertEquals(2, payload.getAsJsonArray("events").size());
		assertTrue(payload.toString().contains("task.failed"));
		assertEquals(3, payload.get("throughEventSequence").getAsLong());
	}

	@Test void separateHistoryCursorsDoNotConsumeEachOthersEvidence() {
		var events = new SemanticEventBuffer(4);
		events.append(1, "task.completed", Map.of("workId", "wood"));
		var context = new PlannerDecisionContext("world", 2, 2, "controller", "idle", Map.of(), events.query(null));
		assertFalse(context.message(1).content().contains("wood"));
		assertTrue(context.forOwner("thinking").message(0).content().contains("wood"));
		assertTrue(context.forOwner("thinking").message(0).content().contains("thinking"));
	}
}
