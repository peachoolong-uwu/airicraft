package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlannerToolCatalogTest {
	@Test void validatesBoundedEntitySearch() {
		PlannerToolCall call = PlannerToolCatalog.parseToolCall(toolCall(PlannerToolCatalog.INSPECT_NEARBY_ENTITIES,
			"{\"radius\":128,\"maxResults\":4,\"entityTypeIds\":[\"minecraft:sheep\"]}"));
		assertEquals(128, call.arguments().get("radius").getAsInt());
		for (String args : java.util.List.of("{\"radius\":129}", "{\"radius\":0}", "{\"radius\":1.5}",
			"{\"maxResults\":65}", "{\"maxResults\":\"4\"}", "{\"entityTypeIds\":[]}"))
			assertThrows(RuntimeException.class, () -> PlannerToolCatalog.parseToolCall(toolCall(PlannerToolCatalog.INSPECT_NEARBY_ENTITIES, args)));
	}

	@Test void validatesAcquisitionScopeAcrossIntentLevels() {
		for (String name : java.util.List.of(PlannerToolCatalog.COLLECT_RESOURCE, PlannerToolCatalog.MINE_BLOCKS, PlannerToolCatalog.ENSURE_BLOCKS_IN_INVENTORY)) {
			String selector = name.equals(PlannerToolCatalog.COLLECT_RESOURCE) ? "\"resourceKind\":\"WOOD_LOGS\"" : "\"blockIds\":[\"minecraft:oak_log\"]";
			String args = "{" + selector + ",\"quantity\":2,\"constraints\":{\"surfaceOnly\":true,\"visibleOnly\":true,\"radius\":24,\"center\":{\"x\":1,\"y\":64,\"z\":-3}}}";
			assertEquals(24, PlannerToolCatalog.parseToolCall(toolCall(name,args)).arguments().getAsJsonObject("constraints").get("radius").getAsInt());
			assertEquals(true, PlannerToolCatalog.parseToolCall(toolCall(name,args)).arguments().getAsJsonObject("constraints").get("visibleOnly").getAsBoolean());
			assertThrows(RuntimeException.class, () -> PlannerToolCatalog.parseToolCall(toolCall(name, args.replace("\"visibleOnly\":true", "\"visibleOnly\":\"true\""))));
			assertThrows(RuntimeException.class, () -> PlannerToolCatalog.parseToolCall(toolCall(name, args.replace("24", "33"))));
			assertThrows(RuntimeException.class, () -> PlannerToolCatalog.parseToolCall(toolCall(name, args.replace("surfaceOnly", "surfaceOnli"))));
			assertThrows(RuntimeException.class, () -> PlannerToolCatalog.parseToolCall(toolCall(name, args.replace("\"y\":64,", ""))));
		}
	}

	@Test
	void parsesDiscoverToolsWithBoundedResultCount() {
		PlannerToolCall call = PlannerToolCatalog.parseToolCall(toolCall("""
			{"query":"smelting","maxResults":3}
			"""));

		assertEquals(PlannerToolCatalog.DISCOVER_TOOLS, call.name());
		assertEquals("smelting", call.arguments().get("query").getAsString());
		assertEquals(3, call.arguments().get("maxResults").getAsInt());
	}

	@Test
	void rejectsDiscoverToolsResultCountOutsideTheCardLimit() {
		assertThrows(RuntimeException.class, () -> PlannerToolCatalog.parseToolCall(toolCall("""
			{"query":"smelting","maxResults":6}
			""")));
	}

	@Test
	void parsesEquipmentAndFoodToolsWithExactItemIds() {
		PlannerToolCall equip = PlannerToolCatalog.parseToolCall(toolCall(
			PlannerToolCatalog.EQUIP_ITEM,
			"{\"itemId\":\"minecraft:iron_chestplate\"}"
		));
		PlannerToolCall eat = PlannerToolCatalog.parseToolCall(toolCall(
			PlannerToolCatalog.EAT_FOOD,
			"{\"itemId\":\"minecraft:bread\"}"
		));

		assertEquals("minecraft:iron_chestplate", equip.arguments().get("itemId").getAsString());
		assertEquals("minecraft:bread", eat.arguments().get("itemId").getAsString());
	}

	private static JsonObject toolCall(String arguments) {
		return toolCall(PlannerToolCatalog.DISCOVER_TOOLS, arguments);
	}

	private static JsonObject toolCall(String name, String arguments) {
		JsonObject toolCall = new JsonObject();
		toolCall.addProperty("id", "call_discover");
		toolCall.addProperty("type", "function");
		JsonObject function = new JsonObject();
		function.addProperty("name", name);
		function.addProperty("arguments", arguments);
		toolCall.add("function", function);
		return toolCall;
	}
}
