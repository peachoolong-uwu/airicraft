package ai.moeru.airicraft.agent.llm;

import java.util.concurrent.CompletableFuture;

public interface CurrentInventoryTool {
	CompletableFuture<String> inspectInventory(String prompt);

	CompletableFuture<String> checkCraftables(String prompt);

	CompletableFuture<String> inspectNearbyEntities(com.google.gson.JsonObject arguments);

	static CurrentInventoryTool disabled() {
		return new CurrentInventoryTool() {
			@Override
			public CompletableFuture<String> inspectInventory(String prompt) {
				return CompletableFuture.completedFuture("INVENTORY_UNAVAILABLE: inventory_tool_disabled");
			}

			@Override
			public CompletableFuture<String> checkCraftables(String prompt) {
				return CompletableFuture.completedFuture("CRAFTABLES_UNAVAILABLE: inventory_tool_disabled");
			}

			@Override
			public CompletableFuture<String> inspectNearbyEntities(com.google.gson.JsonObject arguments) {
				return CompletableFuture.completedFuture("NEARBY_ENTITIES_UNAVAILABLE: inventory_tool_disabled");
			}
		};
	}
}
