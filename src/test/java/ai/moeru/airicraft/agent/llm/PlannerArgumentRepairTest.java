package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.memory.PlaceMemoryToolProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlannerArgumentRepairTest {
	private final PlannerToolRegistry registry = PlannerToolRegistry.of(new PlaceMemoryToolProvider(
		() -> { throw new AssertionError("Parsing must not access Minecraft"); }, Runnable::run));

	@Test void repairsRecordedPlacePositionWithoutChangingRawEvidenceOrText() {
		JsonObject args = JsonParser.parseString("{\"name\":\"shelter\",\"note\":\"{\\\"x\\\":123}\"}").getAsJsonObject();
		args.addProperty("position", "{\"x\":-50,\"y\":66,\"z\":-102}");
		JsonObject wire = wire("remember_place", args.toString());
		PlannerToolCall call = PlannerToolCatalog.parseToolCall(wire, registry);
		assertEquals(-50, call.arguments().getAsJsonObject("position").get("x").getAsInt());
		assertEquals(args.get("note"), call.arguments().get("note"));
		assertEquals(wire, call.rawToolCall());
		assertTrue(args.get("position").isJsonPrimitive());
		assertEquals(java.util.List.of("/position"), call.repairedArgumentPaths());
	}

	@Test void repairsArrayAndNestedObjectBeforeExistingValidation() {
		JsonObject args = new JsonObject();
		args.addProperty("blockIds", "[\"minecraft:oak_log\"]");
		args.addProperty("quantity", 2);
		JsonObject constraints = new JsonObject();
		constraints.addProperty("radius", 24);
		constraints.addProperty("center", "{\"x\":1,\"y\":64,\"z\":-3}");
		args.addProperty("constraints", constraints.toString());
		PlannerToolCall call = PlannerToolCatalog.parseToolCall(wire("mine_blocks", args.toString()), registry);
		assertTrue(call.arguments().get("blockIds").isJsonArray());
		assertEquals(64, call.arguments().getAsJsonObject("constraints").getAsJsonObject("center").get("y").getAsInt());
		assertEquals(java.util.List.of("/blockIds", "/constraints", "/constraints/center"), call.repairedArgumentPaths());
	}

	@Test void repairsAnExtraArgumentsLayerOnlyOnce() {
		var encoded = new com.google.gson.JsonPrimitive("{\"name\":\"shelter\"}");
		var call = PlannerToolCatalog.parseToolCall(wire("remember_place", encoded.toString()), registry);
		assertEquals("shelter", call.arguments().get("name").getAsString());
		assertEquals(java.util.List.of(""), call.repairedArgumentPaths());
		var twice = new com.google.gson.JsonPrimitive(encoded.toString());
		assertThrows(RuntimeException.class, () -> PlannerToolCatalog.parseToolCall(wire("remember_place", twice.toString()), registry));
	}

	@Test void repairsStructuredAdapterArgumentsWithoutMutatingInput() {
		JsonObject args = new JsonObject(); args.addProperty("name", "home");
		args.addProperty("position", "{\"x\":1,\"y\":2,\"z\":3}");
		var call = PlannerToolCatalog.parseToolCall("remember_place", args, registry);
		assertEquals(java.util.List.of("/position"), call.repairedArgumentPaths());
		assertTrue(args.get("position").isJsonPrimitive());
	}

	@Test void preservesCurrentPositionAndRejectsWrongValuesAfterRepair() {
		assertEquals("current", PlannerToolCatalog.parseToolCall(wire("remember_place", "{\"name\":\"home\",\"position\":\"current\"}"), registry)
			.arguments().get("position").getAsString());
		for (String position : java.util.List.of("{\"x\":1,\"y\":2}", "{\"x\":\"1\",\"y\":2,\"z\":3}", "{x:1,y:2,z:3}", "[1,2,3]", "{\"x\":1,\"y\":2,\"z\":3} trailing")) {
			JsonObject args = new JsonObject(); args.addProperty("name", "home"); args.addProperty("position", position);
			assertThrows(RuntimeException.class, () -> PlannerToolCatalog.parseToolCall(wire("remember_place", args.toString()), registry), position);
		}
	}

	static JsonObject wire(String name, String arguments) {
		JsonObject function = new JsonObject(); function.addProperty("name", name); function.addProperty("arguments", arguments);
		JsonObject wire = new JsonObject(); wire.addProperty("id", "call_repair"); wire.addProperty("type", "function"); wire.add("function", function);
		return wire;
	}
}
