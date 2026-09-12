package ai.moeru.airicraft.agent.actions;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PrimitiveActionRegistry {
	private final Map<String, PrimitiveActionMetadata> actions;

	private PrimitiveActionRegistry(Map<String, PrimitiveActionMetadata> actions) {
		this.actions = Map.copyOf(actions);
	}

	public static PrimitiveActionRegistry defaults() {
		LinkedHashMap<String, PrimitiveActionMetadata> actions = new LinkedHashMap<>();
		register(actions, metadata(
			"inspect_inventory",
			"Inspect current inventory item counts.",
			Map.of("prompt", param("string", false, "Optional inventory question.")),
			List.of(),
			List.of("inventory.item", "inventory.tool"),
			List.of("inspection"),
			PrimitiveActionRole.OBSERVATION_ONLY,
			false,
			"CurrentInventoryTool"
		));
		register(actions, metadata(
			"inspect_area",
			"Inspect loaded blocks and entities near the actor.",
			Map.of("radius", param("integer", false, "Search radius.")),
			List.of(),
			List.of("world.block", "world.site", "world.entity"),
			List.of("inspection"),
			PrimitiveActionRole.OBSERVATION_ONLY,
			false,
			"WorldEvidence"
		));
		register(actions, metadata(
			"find_block",
			"Find nearby loaded blocks matching block ids.",
			Map.of(
				"blockIds", param("string[]", true, "Namespaced block ids."),
				"radius", param("integer", false, "Search radius.")
			),
			List.of(),
			List.of("world.block", "world.site"),
			List.of("inspection", "world_scan"),
			PrimitiveActionRole.OBSERVATION_ONLY,
			false,
			"WorldEvidence"
		));
		register(actions, metadata(
			"pathfind_to",
			"Navigate to a block position.",
			Map.of(
				"x", param("integer", true, "Block x coordinate."),
				"y", param("integer", true, "Block y coordinate."),
				"z", param("integer", true, "Block z coordinate."),
				"exactY", param("boolean", false, "True requires exact x/y/z; false navigates to x/z at any height and ignores y.")
			),
			List.of(),
			List.of(),
			List.of("movement"),
			PrimitiveActionRole.FOREGROUND_EXECUTABLE,
			true,
			"WorldTaskRequest.NAVIGATE"
		));
		register(actions, metadata(
			"collect_resource",
			"Collect an inventory resource kind using the existing resource executor.",
			Map.of(
				"resourceKind", param("string", true, "Supported resource kind."),
				"quantity", param("integer", true, "Desired resource count.")
			),
			List.of(),
			List.of("inventory.resource"),
			List.of("collection"),
			PrimitiveActionRole.FOREGROUND_EXECUTABLE,
			true,
			"WorldTaskRequest.COLLECT_RESOURCE"
		));
		register(actions, metadata(
			"mine_block",
			"Mine matching blocks.",
			Map.of(
				"blockIds", param("string[]", true, "Namespaced block ids."),
				"quantity", param("integer", true, "Number of blocks to mine.")
			),
			List.of(),
			List.of("inventory.item"),
			List.of("mining", "destructive"),
			PrimitiveActionRole.FOREGROUND_EXECUTABLE,
			true,
			"WorldTaskRequest.MINE"
		));
		register(actions, metadata(
			"interact_block",
			"Use or interact with a block.",
			Map.of(
				"x", param("integer", true, "Block x coordinate."),
				"y", param("integer", true, "Block y coordinate."),
				"z", param("integer", true, "Block z coordinate."),
				"itemId", param("string", false, "Optional held item id.")
			),
			List.of("world.block"),
			List.of("world.block"),
			List.of("block_interaction"),
			PrimitiveActionRole.RESERVED,
			true,
			"pending"
		));
		register(actions, metadata(
			"till_soil",
			"Till a soil block into farmland with an inventory hoe.",
			Map.of(
				"itemId", param("string", true, "Namespaced hoe item id."),
				"x", param("integer", true, "Ground block x coordinate."),
				"y", param("integer", true, "Ground block y coordinate."),
				"z", param("integer", true, "Ground block z coordinate.")
			),
			List.of("world.block", "inventory.tool"),
			List.of("world.block", "world.farm_plot"),
			List.of("block_interaction", "farming"),
			PrimitiveActionRole.FOREGROUND_EXECUTABLE,
			true,
			"WorldTaskRequest.USE_BLOCK"
		));
		register(actions, metadata(
			"plant_crop",
			"Plant a crop item above farmland.",
			Map.of(
				"itemId", param("string", true, "Namespaced seed or crop item id."),
				"x", param("integer", true, "Crop target x coordinate."),
				"y", param("integer", true, "Crop target y coordinate."),
				"z", param("integer", true, "Crop target z coordinate.")
			),
			List.of("world.block", "inventory.item"),
			List.of("world.crop", "world.crop_group"),
			List.of("block_interaction", "farming"),
			PrimitiveActionRole.FOREGROUND_EXECUTABLE,
			true,
			"WorldTaskRequest.USE_BLOCK"
		));
		register(actions, metadata(
			"hydrate_farmland",
			"Place water for a farm hydration source.",
			Map.of(
				"itemId", param("string", false, "Namespaced water item id; defaults to minecraft:water_bucket."),
				"x", param("integer", true, "Water target x coordinate."),
				"y", param("integer", true, "Water target y coordinate."),
				"z", param("integer", true, "Water target z coordinate.")
			),
			List.of("world.block", "inventory.item"),
			List.of("world.block", "world.hydration_source"),
			List.of("block_interaction", "farming"),
			PrimitiveActionRole.FOREGROUND_EXECUTABLE,
			true,
			"WorldTaskRequest.USE_BLOCK"
		));
		register(actions, metadata(
			"clear_farm_site",
			"Clear one obstructing block from a farm site.",
			Map.of(
				"x", param("integer", true, "Block x coordinate."),
				"y", param("integer", true, "Block y coordinate."),
				"z", param("integer", true, "Block z coordinate."),
				"expectedBlockIds", param("string[]", true, "Expected block ids that may be cleared.")
			),
			List.of("world.block"),
			List.of("world.block", "world.farm_plot"),
			List.of("block_interaction", "farming", "destructive"),
			PrimitiveActionRole.FOREGROUND_EXECUTABLE,
			true,
			"WorldTaskRequest.BREAK_BLOCKS"
		));
		register(actions, metadata(
			"place_block",
			"Place an inventory block item at a position.",
			Map.of(
				"itemId", param("string", true, "Namespaced item id."),
				"x", param("integer", true, "Block x coordinate."),
				"y", param("integer", true, "Block y coordinate."),
				"z", param("integer", true, "Block z coordinate.")
			),
			List.of("inventory.item"),
			List.of("world.block"),
			List.of("block_interaction", "destructive"),
			PrimitiveActionRole.RESERVED,
			true,
			"pending"
		));
		register(actions, metadata(
			"use_item",
			"Use an item with an optional target.",
			Map.of("itemId", param("string", true, "Namespaced item id.")),
			List.of("inventory.item"),
			List.of("world.block", "world.entity"),
			List.of("item_use"),
			PrimitiveActionRole.RESERVED,
			true,
			"pending"
		));
		register(actions, metadata(
			"craft_item",
			"Craft an item from available recipe evidence.",
			Map.of(
				"itemId", param("string", true, "Namespaced output item id."),
				"quantity", param("integer", true, "Desired output count.")
			),
			List.of("craft.recipe"),
			List.of("inventory.item"),
			List.of("crafting"),
			PrimitiveActionRole.FOREGROUND_EXECUTABLE,
			true,
			"WorldTaskRequest.CRAFT_RECIPE"
		));
		register(actions, metadata(
			"smelt_item",
			"Smelt an inventory item through a current smelting option.",
			Map.of(
				"itemId", param("string", true, "Namespaced output item id."),
				"optionId", param("string", true, "Current smelting option id."),
				"inputQuantity", param("integer", true, "Input items to smelt.")
			),
			List.of("smelt.recipe", "inventory.item"),
			List.of("inventory.item"),
			List.of("smelting"),
			PrimitiveActionRole.FOREGROUND_EXECUTABLE,
			true,
			"WorldTaskRequest.SMELT_ITEMS"
		));
		register(actions, metadata(
			"collect_smelted_item",
			"Collect output from an Airicraft-owned smelting process.",
			Map.of("itemId", param("string", false, "Expected output item id.")),
			List.of("watch.fulfilled"),
			List.of("inventory.item"),
			List.of("smelting"),
			PrimitiveActionRole.FOREGROUND_EXECUTABLE,
			true,
			"WorldTaskRequest.COLLECT_SMELTED_ITEMS"
		));
		register(actions, metadata(
			"attack_entity",
			"Attack a nearby entity.",
			Map.of("uuid", param("string", true, "Entity uuid token.")),
			List.of("world.entity"),
			List.of("world.entity", "inventory.item"),
			List.of("combat", "destructive"),
			PrimitiveActionRole.RESERVED,
			true,
			"WorldTaskRequest.ATTACK_ENTITY"
		));
		register(actions, metadata(
			"wait_for_fact",
			"Register a background watch for a fact.",
			Map.of("fact", param("object", true, "Watched typed fact.")),
			List.of(),
			List.of("watch.pending", "watch.fulfilled"),
			List.of("watch"),
			PrimitiveActionRole.RESERVED,
			true,
			"ActionGraphWatch"
		));
		return new PrimitiveActionRegistry(actions);
	}

	public boolean contains(String id) {
		return actions.containsKey(id);
	}

	public PrimitiveActionMetadata require(String id) {
		PrimitiveActionMetadata metadata = actions.get(id);
		if (metadata == null) {
			throw new IllegalArgumentException("Unknown primitive action: " + id);
		}
		return metadata;
	}

	public PrimitiveActionMetadata find(String id) {
		return actions.get(id);
	}

	public Collection<PrimitiveActionMetadata> all() {
		return actions.values();
	}

	public Collection<PrimitiveActionMetadata> executable() {
		return actions.values().stream()
			.filter(PrimitiveActionMetadata::executable)
			.toList();
	}

	private static PrimitiveActionMetadata metadata(
		String id,
		String summary,
		Map<String, PrimitiveParameter> params,
		List<String> guardFacts,
		List<String> producedFacts,
		List<String> tags,
		PrimitiveActionRole role,
		boolean cancellable,
		String executorBinding
	) {
		return new PrimitiveActionMetadata(
			id,
			1,
			summary,
			params,
			guardFacts,
			List.of(),
			producedFacts,
			List.of(),
			10,
			List.of("session_gate", "timeout", "cancelled", "executor_failed"),
			role,
			cancellable,
			20 * 60,
			tags,
			executorBinding
		);
	}

	private static PrimitiveParameter param(String type, boolean required, String summary) {
		return new PrimitiveParameter(type, required, summary);
	}

	private static void register(LinkedHashMap<String, PrimitiveActionMetadata> actions, PrimitiveActionMetadata metadata) {
		actions.put(metadata.id(), metadata);
	}
}
