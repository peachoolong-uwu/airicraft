package ai.moeru.airicraft.agent.llm.codex;

import ai.moeru.airicraft.agent.llm.PlannerToolRegistry;
import ai.moeru.airicraft.agent.memory.PlaceMemoryToolProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CodexPlannerResponseCodecTest {
	@Test void sharesStructureRepairWithNativeToolCalls() throws Exception {
		var registry = PlannerToolRegistry.of(new PlaceMemoryToolProvider(
			() -> { throw new AssertionError("No world access"); }, Runnable::run));
		JsonObject args = new JsonObject(); args.addProperty("name", "home");
		args.addProperty("position", "{\"x\":1,\"y\":2,\"z\":3}");
		JsonObject proposal = new JsonObject(); proposal.addProperty("name", "remember_place");
		proposal.addProperty("argumentsJson", new JsonPrimitive(args.toString()).toString());
		JsonArray calls = new JsonArray(); calls.add(proposal);
		JsonObject response = new JsonObject(); response.add("toolCalls", calls); response.add("chatMessages", new JsonArray());
		var result = new CodexPlannerResponseCodec(registry).parse(response.toString(), 1);
		assertEquals(1, result.toolCall().arguments().getAsJsonObject("position").get("x").getAsInt());
		assertEquals(java.util.List.of("", "/position"), result.toolCall().repairedArgumentPaths());
	}
}
