package ai.moeru.airicraft.agent.llm;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PlannerNumberPresentationTest {
	@Test void roundsEvidenceIncludingNestedJsonNegativesAndScientificNotation() {
		assertEquals("risk=121.3; {\"standingRisk\":175.1,\"facing\":{\"pitch\":68.3,\"yaw\":-21.3},\"tiny\":0,\"whole\":66}; delta=1.3",
			new PlannerReferences().present("risk=121.31318561980031; {\"standingRisk\":175.13384099878414,\"facing\":{\"pitch\":68.29244790474574,\"yaw\":-21.316162109375},\"tiny\":-1.2e-8,\"whole\":66}; delta=1.25"));
	}
	@Test void preservesIntegersIdentifiersQuotedStringsAndUrls() {
		String text = "tick 123456789012345 version 1.21.8 http://127.0.0.1:8765/file/1.234 id_ab12.345 {\"id\":\"1.234\",\"field_1352\":2}";
		assertEquals(text, new PlannerReferences().present(text));
	}
	@Test void wirePresentationRoundsWithoutMutatingCanonicalMessages() {
		var input = List.<Map<String,Object>>of(Map.of("role", "tool", "tool_call_id", "call_1.234", "content", "risk=121.31318561980031"));
		var wire = new PlannerReferences().presentMessages(input).get(0).getAsJsonObject();
		assertEquals("risk=121.3", wire.get("content").getAsString());
		assertEquals("call_1.234", wire.get("tool_call_id").getAsString());
		assertEquals("risk=121.31318561980031", input.getFirst().get("content"));
	}
}
