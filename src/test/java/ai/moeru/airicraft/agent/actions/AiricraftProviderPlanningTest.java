package ai.moeru.airicraft.agent.actions;

import org.junit.jupiter.api.Test;

import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;
import ai.moeru.airicraft.agent.tasks.SmeltingRecipeKnowledge;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiricraftProviderPlanningTest {
	private static final BlockAcquisitionIndex TEST_BLOCK_ACQUISITIONS = BlockAcquisitionTestFixtures.survival();
	private static final ActionResolverContext CONTEXT = new ActionResolverContext(
		"world-a",
		"bot",
		"minecraft:overworld",
		100
	);

	@Test void nearbyWorkbenchAvoidsPortableTableMaterials() {
		ActionFactStore facts = bedPlanningFacts(6, 3);
		facts.upsert(new ActionFact(ActionFactIdentity.worldSite("world-a", "minecraft:overworld", "crafting-table:255,63,481"),
			Map.of("kind", "crafting_table", "availableToActor", "bot"), ActionFactProvenance.OBSERVED, 100, 101));
		var result = resolve(facts, BlockAcquisitionIndex.empty(), NearbyBlockAvailability.unknown(), ActionGoal.inventoryItem("minecraft:white_bed", 1));
		assertTrue(result.resolved());
		assertEquals(List.of("bed"), result.route().steps().stream().map(step -> step.args().get("recipeId")).toList());
	}

	@Test void portableTableMaterialsAreReservedOncePerCraftingBatch() {
		var result = resolve(bedPlanningFacts(10, 6), BlockAcquisitionIndex.empty(), NearbyBlockAvailability.unknown(), ActionGoal.inventoryItem("minecraft:white_bed", 2));
		assertTrue(result.resolved());
		assertEquals(List.of("bed"), result.route().steps().stream().map(step -> step.args().get("recipeId")).toList());
	}

	@Test void staleWorkbenchDoesNotRemovePortableSetupRequirement() {
		ActionFactStore facts = bedPlanningFacts(6, 3);
		facts.upsert(new ActionFact(ActionFactIdentity.worldSite("world-a", "minecraft:overworld", "crafting-table:255,63,481"),
			Map.of("kind", "crafting_table", "availableToActor", "bot"), ActionFactProvenance.OBSERVED, 99, 100));
		var result = resolve(facts, BlockAcquisitionIndex.empty(), NearbyBlockAvailability.unknown(), ActionGoal.inventoryItem("minecraft:white_bed", 1));
		assertTrue(result.resolved());
		assertEquals(List.of("planks", "bed"), result.route().steps().stream().map(step -> step.args().get("recipeId")).toList());
	}

	private static ActionFactStore bedPlanningFacts(int planks, int wool) {
		var facts = new ActionFactStore();
		Map.of("minecraft:pale_oak_planks", planks, "minecraft:white_wool", wool, "minecraft:pale_oak_log", 2).forEach((item, count) ->
			facts.upsert(new ActionFact(ActionFactIdentity.inventoryItem("world-a", "bot", item), Map.of("count", count), ActionFactProvenance.OBSERVED, 90, ActionFact.NEVER_STALE)));
		facts.upsert(new ActionFact(ActionFactIdentity.craftRecipe("world-a", "bot", "bed"),
			Map.of("outputItemId", "minecraft:white_bed", "outputCount", 1, "inputCounts", Map.of("minecraft:pale_oak_planks", 3, "minecraft:white_wool", 3), "gridKind", "WORKBENCH_3X3"),
			ActionFactProvenance.OBSERVED, 90, ActionFact.NEVER_STALE));
		addCraftRecipeFact(facts, "planks", "minecraft:pale_oak_planks", 4, Map.of("minecraft:pale_oak_log", 1));
		return facts;
	}

	@Test
	void productionPlanningPlansDiamondFromEmptyInventoryWithFullRecipeNoise() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);
		addSurvivalSmeltFacts(facts);
		for (int index = 0; index < 1_407; index++) {
			facts.upsert(new ActionFact(
				ActionFactIdentity.craftRecipe("world-a", "bot", "noise_recipe_" + index),
				Map.of(
					"outputItemId", "minecraft:noise_" + index,
					"outputCount", 1,
					"inputCounts", Map.of("minecraft:noise_input_" + index, 1)
				),
				ActionFactProvenance.INFERRED,
				90,
				ActionFact.NEVER_STALE
			));
		}
		ActionResolveResult result = assertTimeoutPreemptively(
			Duration.ofSeconds(1),
			() -> resolve(facts, ActionGoal.inventoryItem("minecraft:diamond", 1))
		);

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals("mine_block", result.route().steps().getLast().targetId());
		assertEquals("minecraft:diamond", result.route().steps().getLast().args().get("itemId"));
	}

	@Test
	void diamondResourceCollectionMinesOreInsteadOfMiningAStorageBlockRecipeInput() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:iron_pickaxe"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		addCraftRecipeFact(
			facts,
			"diamond_block_to_diamond",
			"minecraft:diamond",
			9,
			Map.of("minecraft:diamond_block", 1)
		);
		BlockAcquisitionIndex acquisitions = BlockAcquisitionIndex.of(
			java.util.stream.Stream.concat(
				TEST_BLOCK_ACQUISITIONS.rules().stream(),
				java.util.stream.Stream.of(new BlockAcquisitionRule(
					"minecraft:diamond_block",
					"minecraft:diamond_block",
					List.of("minecraft:iron_pickaxe"),
					false,
					false,
					"minecraft:blocks/diamond_block"
				))
			).toList()
		);

		ActionResolveResult result = resolve(
			facts,
			acquisitions,
			NearbyBlockAvailability.unknown(),
			ActionGoal.resourceCollection("DIAMOND", 1)
		);

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertTrue(result.route().steps().stream().noneMatch(step ->
			"diamond_block_to_diamond".equals(step.args().get("recipeId"))
				|| List.of("minecraft:diamond_block").equals(step.args().get("blockIds"))
		));
		ActionPlanStep mining = result.route().steps().getLast();
		assertEquals("mine_block", mining.targetId());
		assertTrue(List.of(
			List.of("minecraft:diamond_ore"),
			List.of("minecraft:deepslate_diamond_ore")
		).contains(mining.args().get("blockIds")));
	}

	@Test
	void unobservedCraftableStorageBlockIsNotMinedForRecipeInput() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalSmeltFacts(facts);
		addCraftRecipeFact(
			facts,
			"iron_block_to_iron_ingot",
			"minecraft:iron_ingot",
			9,
			Map.of("minecraft:iron_block", 1)
		);
		addCraftRecipeFact(
			facts,
			"iron_ingot_x9_to_iron_block",
			"minecraft:iron_block",
			1,
			Map.of("minecraft:iron_ingot", 9)
		);
		for (Map.Entry<String, Integer> entry : Map.of(
			"minecraft:stone_pickaxe", 1,
			"minecraft:furnace", 1,
			"minecraft:oak_log", 1
		).entrySet()) {
			facts.upsert(new ActionFact(
				ActionFactIdentity.inventoryItem("world-a", "bot", entry.getKey()),
				Map.of("count", entry.getValue()),
				ActionFactProvenance.OBSERVED,
				90,
				ActionFact.NEVER_STALE
			));
		}
		BlockAcquisitionIndex acquisitions = BlockAcquisitionIndex.of(
			java.util.stream.Stream.concat(
				TEST_BLOCK_ACQUISITIONS.rules().stream(),
				java.util.stream.Stream.of(new BlockAcquisitionRule(
					"minecraft:iron_block",
					"minecraft:iron_block",
					List.of("minecraft:stone_pickaxe"),
					false,
					false,
					"minecraft:blocks/iron_block"
				))
			).toList()
		);

		ActionResolveResult result = resolve(
			facts,
			acquisitions,
			NearbyBlockAvailability.observed(Map.of()),
			ActionGoal.inventoryItem("minecraft:iron_ingot", 3)
		);

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertTrue(result.route().steps().stream().noneMatch(step ->
			List.of("minecraft:iron_block").equals(step.args().get("blockIds"))
		));
		assertTrue(result.route().steps().stream().anyMatch(step ->
			"minecraft:raw_iron".equals(step.args().get("itemId"))
		));
	}

	@Test
	void toolRecipeCraftsSticksInsteadOfBreakingDozensOfLeaves() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);

		ActionResolveResult result = resolve(
			facts,
			oakStickAcquisitions(),
			NearbyBlockAvailability.observed(Map.of("minecraft:oak_leaves", 64)),
			ActionGoal.inventoryItem("minecraft:wooden_pickaxe", 1)
		);

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertTrue(result.route().steps().stream().noneMatch(step ->
			List.of("minecraft:oak_leaves").equals(step.args().get("blockIds"))
		));
		assertTrue(result.route().steps().stream().anyMatch(step ->
			"minecraft:stick".equals(step.args().get("itemId"))
				&& "craft_item".equals(step.targetId())
		));
	}

	@Test
	void resolvesResourceCollectionThroughResourceProvider() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryResource("world-a", "bot", "WOOD_LOGS"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.resourceCollection("WOOD_LOGS", 3));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("collect_resource"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep step = result.route().steps().getFirst();
		assertEquals("resource_provider", step.actionId());
		assertEquals("WOOD_LOGS", step.args().get("resourceKind"));
		assertEquals(2, step.args().get("quantity"));
		assertTrace(result.trace(), "route_selected", "resource_provider", "WOOD_LOGS");
	}

	@Test
	void resolvesCatalogedResourceCollectionThroughInventoryMiningProvider() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);

		ActionResolveResult result = resolve(facts, ActionGoal.resourceCollection("RAW_IRON", 3));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertTrue(result.route().steps().stream().anyMatch(step -> "collect_resource".equals(step.targetId())));
		assertTrue(result.route().steps().stream().anyMatch(step -> "craft_item".equals(step.targetId())
			&& "minecraft:wooden_pickaxe".equals(step.args().get("itemId"))));
		assertTrue(result.route().steps().stream().anyMatch(step -> "craft_item".equals(step.targetId())
			&& "minecraft:stone_pickaxe".equals(step.args().get("itemId"))));
		assertTrue(result.route().steps().stream().anyMatch(step -> "mine_block".equals(step.targetId())
			&& "minecraft:raw_iron".equals(step.args().get("itemId"))));
		assertEquals("WOOD_LOGS", result.route().steps().getFirst().args().get("resourceKind"));
		assertEquals("minecraft:raw_iron", result.route().steps().getLast().args().get("itemId"));
		assertTrace(result.trace(), "route_selected", "resource_provider", null);
		assertTracePayload(result.trace(), "route_candidate_built", "mining_provider", "itemId", "minecraft:raw_iron");
	}

	@Test
	void rejectsUnsupportedResourceCollectionWithTrace() {
		ActionResolveResult result = resolve(new ActionFactStore(), ActionGoal.resourceCollection("OBSIDIAN", 1));

		assertFalse(result.resolved());
		assertEquals("no_route", result.failureCode());
		assertTrace(result.trace(), "route_candidate_rejected", "resource_provider", "OBSIDIAN");
	}

	@Test
	void knownMiningAcquisitionDoesNotRequireAnObservedTarget() {
		ActionResolveResult result = resolve(new ActionFactStore(), ActionGoal.inventoryItem("minecraft:dirt", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals("mine_block", result.route().steps().getLast().targetId());
		assertEquals(List.of("minecraft:dirt"), result.route().steps().getLast().args().get("blockIds"));
	}

	@Test
	void resolvesInventoryLogThroughLootTableMiningProvider() {
		ActionResolveResult result = resolve(new ActionFactStore(), ActionGoal.inventoryItem("minecraft:birch_log", 2));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("mine_block"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep step = result.route().steps().getFirst();
		assertEquals("mining_provider", step.actionId());
		assertEquals(List.of("minecraft:birch_log"), step.args().get("blockIds"));
		assertEquals(List.of("minecraft:birch_log"), step.args().get("matchingItemIds"));
		assertEquals(2, step.args().get("quantity"));
	}

	@Test
	void resolvesCraftingFromGenericRecipeFact() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:bread"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:wheat"),
			Map.of("count", 5),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "wheat_wheat_wheat_to_bread"),
			Map.of(
				"outputItemId", "minecraft:bread",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:wheat", 3)
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:bread", 2));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep craft = result.route().steps().getFirst();
		assertEquals("recipe_provider", craft.actionId());
		assertEquals("wheat_wheat_wheat_to_bread", craft.args().get("recipeId"));
		assertEquals(1, craft.args().get("quantity"));
		assertTrace(result.trace(), "route_selected", "recipe_provider", "wheat_wheat_wheat_to_bread");
	}

	@Test
	void recipeProviderUsesGoalDeficitForInputsAndCraftQuantity() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:bread"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:wheat"),
			Map.of("count", 6),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "wheat_wheat_wheat_to_bread"),
			Map.of(
				"outputItemId", "minecraft:bread",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:wheat", 3)
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:bread", 3));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep craft = result.route().steps().getFirst();
		assertEquals("recipe_provider", craft.actionId());
		assertEquals("wheat_wheat_wheat_to_bread", craft.args().get("recipeId"));
		assertEquals(2, craft.args().get("quantity"));
		assertTrace(result.trace(), "route_selected", "recipe_provider", "wheat_wheat_wheat_to_bread");
	}

	@Test
	void resolvesNestedCraftingRecipesFromLogToSticks() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:oak_log"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "oak_log_to_oak_planks"),
			Map.of(
				"outputItemId", "minecraft:oak_planks",
				"outputCount", 4,
				"inputCounts", Map.of("minecraft:oak_log", 1)
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "oak_planks_x2_to_stick"),
			Map.of(
				"outputItemId", "minecraft:stick",
				"outputCount", 4,
				"inputCounts", Map.of("minecraft:oak_planks", 2)
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:stick", 4));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("craft_item", "craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		assertEquals("oak_log_to_oak_planks", result.route().steps().get(0).args().get("recipeId"));
		assertEquals("oak_planks_x2_to_stick", result.route().steps().get(1).args().get("recipeId"));
	}

	@Test
	void recipeCostEqualsOnlyTheMissingIngredientRouteCost() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:test_ingredient"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		addCraftRecipeFact(
			facts,
			"test_ingredient_x2_to_test_result",
			"minecraft:test_result",
			1,
			Map.of("minecraft:test_ingredient", 2)
		);
		BlockAcquisitionIndex acquisitions = BlockAcquisitionIndex.of(List.of(scoredHandRule(
			"minecraft:test_ingredient_ore",
			"minecraft:test_ingredient",
			1.0,
			12
		)));
		NearbyBlockAvailability availability = NearbyBlockAvailability.observed(Map.of(
			"minecraft:test_ingredient_ore", 4
		));

		ActionResolveResult ingredient = resolveWithScoring(
			facts,
			acquisitions,
			availability,
			ActionGoal.inventoryItem("minecraft:test_ingredient", 2)
		);
		ActionResolveResult crafted = resolveWithScoring(
			facts,
			acquisitions,
			availability,
			ActionGoal.inventoryItem("minecraft:test_result", 1)
		);

		assertTrue(ingredient.resolved(), () -> ingredient.trace().toString());
		assertTrue(crafted.resolved(), () -> crafted.trace().toString());
		assertEquals(ingredient.route().cost(), crafted.route().cost());
		assertEquals(
			List.of("mine_block", "craft_item"),
			crafted.route().steps().stream().map(ActionPlanStep::targetId).toList()
		);
	}

	@Test
	void recipeHasZeroIntrinsicCostWhenAllIngredientsAreHeld() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:test_ingredient"),
			Map.of("count", 2),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		addCraftRecipeFact(
			facts,
			"test_ingredient_x2_to_test_result",
			"minecraft:test_result",
			1,
			Map.of("minecraft:test_ingredient", 2)
		);

		ActionResolveResult result = resolveWithScoring(
			facts,
			BlockAcquisitionIndex.empty(),
			NearbyBlockAvailability.unknown(),
			ActionGoal.inventoryItem("minecraft:test_result", 1)
		);

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(0, result.route().cost());
		assertEquals(List.of("craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
	}

	@Test
	void probabilisticMiningCostUsesExpectedAttempts() {
		BlockAcquisitionIndex acquisitions = BlockAcquisitionIndex.of(List.of(
			scoredHandRule("minecraft:deterministic_source", "minecraft:deterministic_drop", 1.0, 10),
			scoredHandRule("minecraft:probabilistic_source", "minecraft:probabilistic_drop", 0.2, 10)
		));

		ActionResolveResult deterministic = resolveWithScoring(
			new ActionFactStore(),
			acquisitions,
			NearbyBlockAvailability.unknown(),
			ActionGoal.inventoryItem("minecraft:deterministic_drop", 1)
		);
		ActionResolveResult probabilistic = resolveWithScoring(
			new ActionFactStore(),
			acquisitions,
			NearbyBlockAvailability.unknown(),
			ActionGoal.inventoryItem("minecraft:probabilistic_drop", 1)
		);

		assertTrue(deterministic.resolved(), () -> deterministic.trace().toString());
		assertTrue(probabilistic.resolved(), () -> probabilistic.trace().toString());
		assertEquals(deterministic.route().cost() * 5, probabilistic.route().cost());
	}

	@Test
	void slowerBlocksHaveHigherMiningCost() {
		BlockAcquisitionIndex acquisitions = BlockAcquisitionIndex.of(List.of(
			scoredHandRule("minecraft:fast_source", "minecraft:fast_drop", 1.0, 5),
			scoredHandRule("minecraft:slow_source", "minecraft:slow_drop", 1.0, 40)
		));

		ActionResolveResult fast = resolveWithScoring(
			new ActionFactStore(),
			acquisitions,
			NearbyBlockAvailability.unknown(),
			ActionGoal.inventoryItem("minecraft:fast_drop", 1)
		);
		ActionResolveResult slow = resolveWithScoring(
			new ActionFactStore(),
			acquisitions,
			NearbyBlockAvailability.unknown(),
			ActionGoal.inventoryItem("minecraft:slow_drop", 1)
		);

		assertTrue(fast.resolved(), () -> fast.trace().toString());
		assertTrue(slow.resolved(), () -> slow.trace().toString());
		assertTrue(slow.route().cost() > fast.route().cost(), () -> "fast=" + fast.route() + ", slow=" + slow.route());
	}

	@Test
	void abundantSourceBeatsEquallyFastScarceSource() {
		BlockAcquisitionIndex acquisitions = BlockAcquisitionIndex.of(List.of(
			scoredHandRule("minecraft:a_scarce_source", "minecraft:shared_drop", 1.0, 10),
			scoredHandRule("minecraft:z_abundant_source", "minecraft:shared_drop", 1.0, 10)
		));

		ActionResolveResult result = resolveWithScoring(
			new ActionFactStore(),
			acquisitions,
			NearbyBlockAvailability.observed(Map.of(
				"minecraft:a_scarce_source", 1,
				"minecraft:z_abundant_source", 10
			)),
			ActionGoal.inventoryItem("minecraft:shared_drop", 10)
		);

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("minecraft:z_abundant_source"), result.route().steps().getLast().args().get("blockIds"));
	}

	@Test
	void nearbyLogsMakeCraftingSticksCheaperThanLeafDrops() {
		ActionFactStore facts = oakStickCraftFacts();
		BlockAcquisitionIndex acquisitions = oakStickAcquisitions();

		ActionResolveResult result = resolveWithScoring(
			facts,
			acquisitions,
			NearbyBlockAvailability.observed(Map.of(
				"minecraft:oak_log", 2,
				"minecraft:oak_leaves", 64
			)),
			ActionGoal.inventoryItem("minecraft:stick", 2)
		);

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals("collect_resource", result.route().steps().getFirst().targetId());
		assertEquals("craft_item", result.route().steps().getLast().targetId());
		assertEquals("oak_planks_x2_to_stick", result.route().steps().getLast().args().get("recipeId"));
		assertFalse(result.route().steps().stream().anyMatch(step ->
			"mine_block".equals(step.targetId())
				&& List.of("minecraft:oak_leaves").equals(step.args().get("blockIds"))
		));
		assertTrace(result.trace(), "route_selected", "recipe_provider", "oak_planks_x2_to_stick");
	}

	@Test
	void absentLogsStillBeatAbsentDeadBushesForSticks() {
		ActionResolveResult result = resolveWithScoring(
			oakStickCraftFacts(),
			oakLogAndDeadBushStickAcquisitions(),
			NearbyBlockAvailability.observed(Map.of()),
			ActionGoal.inventoryItem("minecraft:stick", 2)
		);

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals("collect_resource", result.route().steps().getFirst().targetId());
		assertEquals("craft_item", result.route().steps().getLast().targetId());
		assertEquals("oak_planks_x2_to_stick", result.route().steps().getLast().args().get("recipeId"));
		assertFalse(result.route().steps().stream().anyMatch(step ->
			List.of("minecraft:dead_bush").equals(step.args().get("blockIds"))
		));
	}

	@Test
	void observedDeadBushesRemainFallbackWhenLogsAreAbsent() {
		ActionResolveResult result = resolveWithScoring(
			oakStickCraftFacts(),
			oakLogAndDeadBushStickAcquisitions(),
			NearbyBlockAvailability.observed(Map.of("minecraft:dead_bush", 2)),
			ActionGoal.inventoryItem("minecraft:stick", 2)
		);

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("mine_block"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		assertEquals(List.of("minecraft:dead_bush"), result.route().steps().getFirst().args().get("blockIds"));
	}

	@Test
	void leafDropsRemainFallbackWhenObservedLogsAreGone() {
		ActionResolveResult result = resolveWithScoring(
			oakStickCraftFacts(),
			oakStickAcquisitions(),
			NearbyBlockAvailability.observed(Map.of("minecraft:oak_leaves", 64)),
			ActionGoal.inventoryItem("minecraft:stick", 2)
		);

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("mine_block"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep mine = result.route().steps().getFirst();
		assertEquals("minecraft:stick", mine.args().get("itemId"));
		assertEquals(List.of("minecraft:oak_leaves"), mine.args().get("blockIds"));
	}

	@Test
	void absentPlacedPlanksDoNotBeatCraftingFromNearbyLogs() {
		ActionFactStore facts = new ActionFactStore();
		addCraftRecipeFact(
			facts,
			"oak_log_to_oak_planks",
			"minecraft:oak_planks",
			4,
			Map.of("minecraft:oak_log", 1)
		);
		BlockAcquisitionIndex acquisitions = BlockAcquisitionIndex.of(List.of(
			scoredHandRule("minecraft:oak_log", "minecraft:oak_log", 1.0, 20),
			scoredHandRule("minecraft:oak_planks", "minecraft:oak_planks", 1.0, 10)
		));

		ActionResolveResult result = resolveWithScoring(
			facts,
			acquisitions,
			NearbyBlockAvailability.observed(Map.of("minecraft:oak_log", 2)),
			ActionGoal.inventoryItem("minecraft:oak_planks", 4)
		);

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(
			List.of("collect_resource", "craft_item"),
			result.route().steps().stream().map(ActionPlanStep::targetId).toList()
		);
		assertEquals("oak_log_to_oak_planks", result.route().steps().getLast().args().get("recipeId"));
		assertFalse(result.route().steps().stream().anyMatch(step ->
			List.of("minecraft:oak_planks").equals(step.args().get("blockIds"))
		));
	}

	@Test
	void absentPlacedFurnaceDoesNotBeatCraftingFromNearbyStone() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:wooden_pickaxe"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:crafting_table"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "cobblestone_x8_to_furnace"),
			Map.of(
				"outputItemId", "minecraft:furnace",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:cobblestone", 8),
				"gridKind", "WORKBENCH_3X3"
			),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));
		BlockAcquisitionIndex acquisitions = BlockAcquisitionIndex.of(List.of(
			scoredToolRule(
				"minecraft:stone",
				"minecraft:cobblestone",
				"minecraft:wooden_pickaxe",
				1.0,
				20
			),
			scoredToolRule(
				"minecraft:furnace",
				"minecraft:furnace",
				"minecraft:wooden_pickaxe",
				1.0,
				30
			)
		));

		ActionResolveResult result = resolveWithScoring(
			facts,
			acquisitions,
			NearbyBlockAvailability.observed(Map.of("minecraft:stone", 16)),
			ActionGoal.inventoryItem("minecraft:furnace", 1)
		);

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(
			List.of("mine_block", "craft_item"),
			result.route().steps().stream().map(ActionPlanStep::targetId).toList()
		);
		assertEquals(List.of("minecraft:stone"), result.route().steps().getFirst().args().get("blockIds"));
		assertEquals("cobblestone_x8_to_furnace", result.route().steps().getLast().args().get("recipeId"));
	}

	@Test
	void recipeProviderPrefersObservedWoodVariantAfterGenericCollection() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_log"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:crafting_table", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("craft_item", "craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		assertEquals("birch_log_to_birch_planks", result.route().steps().get(0).args().get("recipeId"));
		assertEquals("birch_planks_x4_to_crafting_table", result.route().steps().get(1).args().get("recipeId"));
	}

	@Test
	void recipeProviderUsesObservedLogWhenPlankDeficitRemainsAfterReplan() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_log"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_planks"),
			Map.of("count", 2),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stick"),
			Map.of("count", 4),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:wooden_pickaxe", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("collect_resource", "craft_item", "craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		assertEquals("WOOD_LOGS", result.route().steps().getFirst().args().get("resourceKind"));
		assertEquals("birch_log_to_birch_planks", result.route().steps().get(1).args().get("recipeId"));
		assertEquals("birch_planks_x3_and_stick_x2_to_wooden_pickaxe", result.route().steps().get(2).args().get("recipeId"));
	}

	@Test
	void recipeProviderResolvesWoodenHoeForFarmBootstrap() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:oak_planks"),
			Map.of("count", 7),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:wooden_hoe", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("craft_item", "craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		assertEquals("oak_planks_x2_to_stick", result.route().steps().getFirst().args().get("recipeId"));
		assertEquals("oak_planks_x2_and_stick_x2_to_wooden_hoe", result.route().steps().getLast().args().get("recipeId"));
	}

	@Test
	void recipeProviderDoesNotBindUnobservedPlankVariantWhenPartialPlanksNeedMoreWood() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_planks"),
			Map.of("count", 2),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stick"),
			Map.of("count", 4),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:wooden_pickaxe", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals("collect_resource", result.route().steps().getFirst().targetId());
		assertEquals("WOOD_LOGS", result.route().steps().getFirst().args().get("resourceKind"));
		assertFalse(result.route().steps().stream()
			.anyMatch(step -> "acacia_planks_x3_and_stick_x2_to_wooden_pickaxe".equals(step.args().get("recipeId"))));
		assertEquals("birch_planks_x3_and_stick_x2_to_wooden_pickaxe", result.route().steps().getLast().args().get("recipeId"));
	}

	@Test
	void recipeProviderCollectsGenericWoodBeforeChoosingConcreteWoodVariant() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:crafting_table", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals("collect_resource", result.route().steps().getFirst().targetId());
		assertEquals("WOOD_LOGS", result.route().steps().getFirst().alternativeId());
		assertEquals("WOOD_LOGS", result.route().steps().getFirst().args().get("resourceKind"));
	}

	@Test
	void resolvesInventoryItemThroughSmeltingRecipeFact() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:raw_iron"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.smeltRecipe("world-a", "bot", "smelt:minecraft_raw_iron_to_minecraft_iron_ingot:nearby-1"),
			Map.of(
				"inputItemId", "minecraft:raw_iron",
				"outputItemId", "minecraft:iron_ingot",
				"outputCount", 1,
				"maxInputQuantity", 3
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:iron_ingot", 3));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("mine_block", "smelt_item", "watch", "collect_smelted_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep fuel = result.route().steps().getFirst();
		assertEquals("minecraft:oak_log", fuel.args().get("itemId"));
		ActionPlanStep smelt = result.route().steps().get(1);
		assertEquals("smelting_provider", smelt.actionId());
		assertEquals("minecraft:raw_iron", smelt.args().get("inputItemId"));
		assertEquals(3, smelt.args().get("inputQuantity"));
		assertEquals("minecraft:oak_log", smelt.args().get("fuelItemId"));
		assertEquals(2, smelt.args().get("fuelQuantity"));
		assertTrace(result.trace(), "route_selected", "smelting_provider", "smelt:minecraft_raw_iron_to_minecraft_iron_ingot:nearby-1");
	}

	@Test
	void smeltingProviderPrefersAvailableObservedInputOverAlphabeticallyEarlierInferredRecipe() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:oak_log"),
			Map.of("count", 8),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:coal"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:furnace"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.smeltRecipe("world-a", "bot", "inferred:minecraft_acacia_log_to_minecraft_charcoal"),
			Map.of(
				"inputItemId", "minecraft:acacia_log",
				"outputItemId", "minecraft:charcoal",
				"outputCount", 1,
				"maxInputQuantity", 64,
				"cookTimeTicks", 200,
				"stationItemId", "minecraft:furnace",
				"stationItemCount", 1
			),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.smeltRecipe("world-a", "bot", "smelt:minecraft_oak_log_to_minecraft_charcoal:nearby-1"),
			Map.of(
				"inputItemId", "minecraft:oak_log",
				"outputItemId", "minecraft:charcoal",
				"outputCount", 1,
				"maxInputQuantity", 8,
				"cookTimeTicks", 200
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:charcoal", 8));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("smelt_item", "watch", "collect_smelted_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep readyWatch = result.route().steps().get(1);
		assertEquals(ActionStepKind.WATCH, readyWatch.kind());
		assertEquals(ActionFactType.SMELTING_PROCESS, readyWatch.watchSpec().condition().factType());
		assertEquals(Map.of("ready", 1), readyWatch.watchSpec().condition().minimums());
		assertEquals(ActionWatchProgressKind.AREA_TICKING, readyWatch.watchSpec().progressKind());
		assertEquals("smelt:minecraft_oak_log_to_minecraft_charcoal:nearby-1", result.route().steps().getFirst().args().get("optionId"));
		assertTrace(result.trace(), "route_selected", "smelting_provider", "smelt:minecraft_oak_log_to_minecraft_charcoal:nearby-1");
	}

	@Test
	void smeltingProviderPlansFuelSubgoalWhenFuelIsCraftable() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:raw_iron"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_log"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.smeltRecipe("world-a", "bot", "smelt:minecraft_raw_iron_to_minecraft_iron_ingot:nearby-1"),
			Map.of(
				"inputItemId", "minecraft:raw_iron",
				"outputItemId", "minecraft:iron_ingot",
				"outputCount", 1,
				"maxInputQuantity", 3,
				"cookTimeTicks", 200
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:iron_ingot", 3));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("craft_item", "smelt_item", "watch", "collect_smelted_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep fuelCraft = result.route().steps().getFirst();
		assertEquals("minecraft:birch_planks", fuelCraft.args().get("itemId"));
		ActionPlanStep smelt = result.route().steps().get(1);
		assertEquals("minecraft:birch_planks", smelt.args().get("fuelItemId"));
		assertEquals(2, smelt.args().get("fuelQuantity"));
		assertTrace(result.trace(), "fuel_subgoal_planned", "smelting_provider", "minecraft:birch_planks");
	}

	@Test
	void smeltingProviderPrefersMineableCoalOverWoodFuelWhenPickaxeAvailable() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:raw_iron"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_planks"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stone_pickaxe"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.smeltRecipe("world-a", "bot", "smelt:minecraft_raw_iron_to_minecraft_iron_ingot:nearby-1"),
			Map.of(
				"inputItemId", "minecraft:raw_iron",
				"outputItemId", "minecraft:iron_ingot",
				"outputCount", 1,
				"maxInputQuantity", 3,
				"cookTimeTicks", 200
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:iron_ingot", 3));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("mine_block", "smelt_item", "watch", "collect_smelted_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep fuel = result.route().steps().getFirst();
		assertEquals("minecraft:coal", fuel.args().get("itemId"));
		ActionPlanStep smelt = result.route().steps().get(1);
		assertEquals("minecraft:coal", smelt.args().get("fuelItemId"));
		assertEquals(1, smelt.args().get("fuelQuantity"));
		assertTrace(result.trace(), "fuel_subgoal_planned", "smelting_provider", "minecraft:coal");
	}

	@Test
	void directSmeltingBeatsReversibleCraftingRecipes() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:raw_iron"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.smeltRecipe("world-a", "bot", "smelt:minecraft_raw_iron_to_minecraft_iron_ingot:nearby-1"),
			Map.of(
				"inputItemId", "minecraft:raw_iron",
				"outputItemId", "minecraft:iron_ingot",
				"outputCount", 1,
				"maxInputQuantity", 3
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "iron_ingot_x9_to_iron_block"),
			Map.of(
				"outputItemId", "minecraft:iron_block",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:iron_ingot", 9)
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "iron_block_to_iron_ingot"),
			Map.of(
				"outputItemId", "minecraft:iron_ingot",
				"outputCount", 9,
				"inputCounts", Map.of("minecraft:iron_block", 1)
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:iron_ingot", 3));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("mine_block", "smelt_item", "watch", "collect_smelted_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep smelt = result.route().steps().get(1);
		assertEquals(3, smelt.args().get("inputQuantity"));
		assertEquals("minecraft:oak_log", smelt.args().get("fuelItemId"));
		assertFalse(result.route().steps().stream().anyMatch(step -> "iron_block_to_iron_ingot".equals(step.args().get("recipeId"))));
	}

	@Test
	void resolvesMinedDropInventoryItemThroughMiningProvider() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stone_pickaxe"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:raw_iron", 3));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("mine_block"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep mine = result.route().steps().getFirst();
		assertEquals("mining_provider", mine.actionId());
		assertEquals("minecraft:raw_iron", mine.args().get("itemId"));
		assertEquals(3, mine.args().get("quantity"));
		assertEquals(List.of("minecraft:deepslate_iron_ore"), mine.args().get("blockIds"));
		assertTracePayload(result.trace(), "route_candidate_built", "mining_provider", "itemId", "minecraft:raw_iron");
	}

	@Test
	void directMiningBeatsReversibleRawBlockCraftingRecipes() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stone_pickaxe"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "raw_iron_x9_to_raw_iron_block"),
			Map.of(
				"outputItemId", "minecraft:raw_iron_block",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:raw_iron", 9)
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "raw_iron_block_to_raw_iron"),
			Map.of(
				"outputItemId", "minecraft:raw_iron",
				"outputCount", 9,
				"inputCounts", Map.of("minecraft:raw_iron_block", 1)
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:raw_iron", 3));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("mine_block"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep mine = result.route().steps().getFirst();
		assertEquals("minecraft:raw_iron", mine.args().get("itemId"));
		assertEquals(3, mine.args().get("quantity"));
		assertFalse(result.route().steps().stream().anyMatch(step -> "raw_iron_block_to_raw_iron".equals(step.args().get("recipeId"))));
	}

	@Test
	void miningProviderPrependsPickaxePrerequisites() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_planks"),
			Map.of("count", 6),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stick"),
			Map.of("count", 4),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:raw_iron", 3));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("collect_resource", "craft_item", "mine_block", "craft_item", "mine_block"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		assertEquals("WOOD_LOGS", result.route().steps().getFirst().args().get("resourceKind"));
		assertEquals("minecraft:wooden_pickaxe", result.route().steps().get(1).args().get("itemId"));
		assertEquals("minecraft:cobblestone", result.route().steps().get(2).args().get("itemId"));
		assertEquals("minecraft:stone_pickaxe", result.route().steps().get(3).args().get("itemId"));
		assertEquals("minecraft:raw_iron", result.route().steps().get(4).args().get("itemId"));
	}

	@Test
	void ironPickaxeRouteSurvivesPartialBirchToolReplan() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_log"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_planks"),
			Map.of("count", 2),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stick"),
			Map.of("count", 4),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.smeltRecipe("world-a", "bot", "inferred:minecraft_raw_iron_to_minecraft_iron_ingot"),
			Map.of(
				"inputItemId", "minecraft:raw_iron",
				"outputItemId", "minecraft:iron_ingot",
				"outputCount", 1,
				"maxInputQuantity", 3
			),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:iron_pickaxe", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals("collect_resource", result.route().steps().getFirst().targetId());
		assertEquals("WOOD_LOGS", result.route().steps().getFirst().args().get("resourceKind"));
		assertEquals("iron_ingot_x3_and_stick_x2_to_iron_pickaxe", result.route().steps().getLast().args().get("recipeId"));
	}

	@Test
	void ironPickaxeRouteSurvivesReplanAfterFurnaceConsumesCobblestone() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);
		addSurvivalSmeltFacts(facts);
		CraftingOpportunity stonePickaxeRecipe = ActionGraphRecipeFixtures.survivalCrafts().stream()
			.filter(craft -> "minecraft:stone_pickaxe".equals(craft.outputItemId()))
			.findFirst()
			.orElseThrow();
		ActionFactIdentity stonePickaxeRecipeId = ActionFactIdentity.craftRecipe(
			"world-a",
			"bot",
			stonePickaxeRecipe.recipeId()
		);
		facts.upsert(new ActionFact(
			stonePickaxeRecipeId,
			craftPayload(stonePickaxeRecipe),
			ActionFactProvenance.OBSERVED,
			98,
			99
		));
		facts.upsert(new ActionFact(
			stonePickaxeRecipeId,
			craftPayload(stonePickaxeRecipe),
			ActionFactProvenance.INFERRED,
			100,
			ActionFact.NEVER_STALE
		));
		Map<String, Integer> inventory = Map.of(
			"minecraft:birch_planks", 7,
			"minecraft:furnace", 1,
			"minecraft:stick", 2,
			"minecraft:wooden_pickaxe", 1
		);
		for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
			facts.upsert(new ActionFact(
				ActionFactIdentity.inventoryItem("world-a", "bot", entry.getKey()),
				Map.of("count", entry.getValue()),
				ActionFactProvenance.OBSERVED,
				90,
				ActionFact.NEVER_STALE
			));
		}

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:iron_pickaxe", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertTrue(result.route().steps().stream().anyMatch(step ->
			"mine_block".equals(step.targetId())
				&& "minecraft:cobblestone".equals(step.args().get("itemId"))
		), () -> result.route().toString());
		assertTrue(result.route().steps().stream().anyMatch(step ->
			"craft_item".equals(step.targetId())
				&& "minecraft:stone_pickaxe".equals(step.args().get("itemId"))
		), () -> result.route().toString());
		assertEquals("iron_ingot_x3_and_stick_x2_to_iron_pickaxe", result.route().steps().getLast().args().get("recipeId"));
	}

	@Test
	void workbenchSetupPrefersObservedLogSpeciesWhenCarriedPlanksAreInsufficient() {
		ActionFactStore facts = new ActionFactStore();
		addSurvivalCraftFacts(facts);
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:cobblestone"),
			Map.of("count", 8),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_planks"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:spruce_log"),
			Map.of("count", 4),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:furnace", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals("spruce_log_to_spruce_planks", result.route().steps().getFirst().args().get("recipeId"));
		assertEquals("cobblestone_x8_to_furnace", result.route().steps().getLast().args().get("recipeId"));
	}

	@Test
	void recipeProviderCanUseSmeltingProviderForCraftInputs() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:raw_iron"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stick"),
			Map.of("count", 2),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.smeltRecipe("world-a", "bot", "smelt:minecraft_raw_iron_to_minecraft_iron_ingot:nearby-1"),
			Map.of(
				"inputItemId", "minecraft:raw_iron",
				"outputItemId", "minecraft:iron_ingot",
				"outputCount", 1,
				"maxInputQuantity", 3
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "iron_ingot_x3_and_stick_x2_to_iron_pickaxe"),
			Map.of(
				"outputItemId", "minecraft:iron_pickaxe",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:iron_ingot", 3, "minecraft:stick", 2)
			),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:iron_pickaxe", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("mine_block", "smelt_item", "watch", "collect_smelted_item", "craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		assertEquals("minecraft:oak_log", result.route().steps().get(1).args().get("fuelItemId"));
		assertEquals("iron_ingot_x3_and_stick_x2_to_iron_pickaxe", result.route().steps().get(4).args().get("recipeId"));
	}

	@Test
	void workbenchRecipeProviderEnsuresPortableCraftingTableWhenRouteHasNoStation() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_planks"),
			Map.of("count", 4),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:iron_ingot"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stick"),
			Map.of("count", 2),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "birch_planks_x4_to_crafting_table"),
			Map.of(
				"outputItemId", "minecraft:crafting_table",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:birch_planks", 4),
				"gridKind", "PLAYER_2X2"
			),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "iron_ingot_x3_and_stick_x2_to_iron_pickaxe"),
			Map.of(
				"outputItemId", "minecraft:iron_pickaxe",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:iron_ingot", 3, "minecraft:stick", 2),
				"gridKind", "WORKBENCH_3X3"
			),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:iron_pickaxe", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		assertEquals("minecraft:iron_pickaxe", result.route().steps().getFirst().args().get("itemId"));
	}

	@Test
	void workbenchRecipeProviderUsesCarriedCraftingTableAsStation() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:crafting_table"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:iron_ingot"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stick"),
			Map.of("count", 2),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "iron_ingot_x3_and_stick_x2_to_iron_pickaxe"),
			Map.of(
				"outputItemId", "minecraft:iron_pickaxe",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:iron_ingot", 3, "minecraft:stick", 2),
				"gridKind", "WORKBENCH_3X3"
			),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:iron_pickaxe", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		assertEquals("iron_ingot_x3_and_stick_x2_to_iron_pickaxe", result.route().steps().getFirst().args().get("recipeId"));
	}

	@Test
	void workbenchRecipeProviderDoesNotRepeatStationAlreadyPlannedInRoute() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:birch_planks"),
			Map.of("count", 4),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:cobblestone"),
			Map.of("count", 8),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:raw_iron"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:coal"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stick"),
			Map.of("count", 2),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "birch_planks_x4_to_crafting_table"),
			Map.of(
				"outputItemId", "minecraft:crafting_table",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:birch_planks", 4),
				"gridKind", "PLAYER_2X2"
			),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "cobblestone_x8_to_furnace"),
			Map.of(
				"outputItemId", "minecraft:furnace",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:cobblestone", 8),
				"gridKind", "WORKBENCH_3X3"
			),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.smeltRecipe("world-a", "bot", "smelt:minecraft_raw_iron_to_minecraft_iron_ingot:carried_furnace-1"),
			Map.of(
				"inputItemId", "minecraft:raw_iron",
				"outputItemId", "minecraft:iron_ingot",
				"outputCount", 1,
				"maxInputQuantity", 3,
				"stationItemId", "minecraft:furnace",
				"stationItemCount", 1
			),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "iron_ingot_x3_and_stick_x2_to_iron_pickaxe"),
			Map.of(
				"outputItemId", "minecraft:iron_pickaxe",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:iron_ingot", 3, "minecraft:stick", 2),
				"gridKind", "WORKBENCH_3X3"
			),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:iron_pickaxe", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(0, result.route().steps().stream()
			.filter(step -> "minecraft:crafting_table".equals(step.args().get("itemId")))
			.count());
		assertEquals("iron_ingot_x3_and_stick_x2_to_iron_pickaxe", result.route().steps().getLast().args().get("recipeId"));
	}

	@Test
	void recipeProviderCanUseMiningAndSmeltingProvidersForCraftInputs() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stone_pickaxe"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stick"),
			Map.of("count", 2),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.smeltRecipe("world-a", "bot", "smelt:minecraft_raw_iron_to_minecraft_iron_ingot:nearby-1"),
			Map.of(
				"inputItemId", "minecraft:raw_iron",
				"outputItemId", "minecraft:iron_ingot",
				"outputCount", 1,
				"maxInputQuantity", 3
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "iron_ingot_x3_and_stick_x2_to_iron_pickaxe"),
			Map.of(
				"outputItemId", "minecraft:iron_pickaxe",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:iron_ingot", 3, "minecraft:stick", 2)
			),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:iron_pickaxe", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("mine_block", "mine_block", "smelt_item", "watch", "collect_smelted_item", "craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		assertEquals("minecraft:raw_iron", result.route().steps().getFirst().args().get("itemId"));
		assertEquals("minecraft:coal", result.route().steps().get(1).args().get("itemId"));
		assertEquals("minecraft:coal", result.route().steps().get(2).args().get("fuelItemId"));
		assertEquals("iron_ingot_x3_and_stick_x2_to_iron_pickaxe", result.route().steps().get(5).args().get("recipeId"));
	}

	@Test
	void miningProviderPreservesAbsoluteInventoryTargetForPartialCobblestoneDeficit() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:wooden_pickaxe"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:cobblestone"),
			Map.of("count", 7),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:cobblestone", 8));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("mine_block"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep step = result.route().steps().getFirst();
		assertEquals("minecraft:cobblestone", step.args().get("itemId"));
		assertEquals(1, step.args().get("quantity"));
		assertEquals(8, step.args().get("targetCount"));
	}

	@Test
	void returnsNoRouteWhenNoProviderIsRegistered() {
		ActionResolveResult result = resolve(new ActionFactStore(), ActionGoal.inventoryItem("minecraft:elytra", 1));

		assertFalse(result.resolved());
		assertEquals("no_route", result.failureCode());
		assertTrue(result.route().steps().isEmpty());
		assertTrace(result.trace(), "goal_started", null, null);
		assertTrace(result.trace(), "goal_failed", null, null);
	}

	@Test
	void resolvesSeagrassFromBlockAcquisitionKnowledgeWithShears() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:shears"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolve(facts, ActionGoal.inventoryItem("minecraft:seagrass", 20));

		assertTrue(result.resolved(), () -> result.trace().toString());
		ActionPlanStep step = result.route().steps().getLast();
		assertEquals("mine_block", step.targetId());
		assertEquals(List.of("minecraft:seagrass"), step.args().get("blockIds"));
		assertEquals(List.of("minecraft:seagrass"), step.args().get("matchingItemIds"));
		assertEquals(List.of("minecraft:shears"), step.args().get("requiredToolItemIds"));
	}

	private static ActionResolveResult resolve(ActionFactStore facts, ActionGoal goal) {
		return resolve(facts, TEST_BLOCK_ACQUISITIONS, NearbyBlockAvailability.unknown(), goal);
	}

	private static ActionResolveResult resolveWithScoring(
		ActionFactStore facts,
		BlockAcquisitionIndex acquisitions,
		NearbyBlockAvailability availability,
		ActionGoal goal
	) {
		return resolve(facts, acquisitions, availability, goal);
	}

	private static ActionResolveResult resolve(
		ActionFactStore facts,
		BlockAcquisitionIndex acquisitions,
		NearbyBlockAvailability availability,
		ActionGoal goal
	) {
		return new AutoCommittingRoutePlanner().adviseAndCommit(
			new AiricraftPlanningSnapshot(facts.queryAll(), acquisitions, availability, CONTEXT),
			goal,
			Set.of()
		);
	}

	private static ActionFactStore oakStickCraftFacts() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:stone_axe"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		addCraftRecipeFact(
			facts,
			"oak_log_to_oak_planks",
			"minecraft:oak_planks",
			4,
			Map.of("minecraft:oak_log", 1)
		);
		addCraftRecipeFact(
			facts,
			"oak_planks_x2_to_stick",
			"minecraft:stick",
			4,
			Map.of("minecraft:oak_planks", 2)
		);
		return facts;
	}

	private static BlockAcquisitionIndex oakStickAcquisitions() {
		return BlockAcquisitionIndex.of(List.of(
			new BlockAcquisitionRule(
				"minecraft:oak_log",
				"minecraft:oak_log",
				List.of("minecraft:stone_axe"),
				true,
				false,
				"test:blocks/oak_log",
				true,
				1.0,
				1.0,
				60,
				Map.of("minecraft:stone_axe", 8)
			),
			scoredHandRule("minecraft:oak_leaves", "minecraft:stick", 0.03, 6)
		));
	}

	private static BlockAcquisitionIndex oakLogAndDeadBushStickAcquisitions() {
		return BlockAcquisitionIndex.of(List.of(
			new BlockAcquisitionRule(
				"minecraft:oak_log",
				"minecraft:oak_log",
				List.of("minecraft:stone_axe"),
				true,
				false,
				"test:blocks/oak_log",
				true,
				1.0,
				1.0,
				60,
				Map.of("minecraft:stone_axe", 8)
			),
			scoredHandRule("minecraft:dead_bush", "minecraft:stick", 1.0, 1)
		));
	}

	private static BlockAcquisitionRule scoredHandRule(
		String blockId,
		String outputItemId,
		double expectedDropsPerBreak,
		int breakTicks
	) {
		boolean probabilistic = expectedDropsPerBreak < 1.0;
		return new BlockAcquisitionRule(
			blockId,
			outputItemId,
			List.of(),
			true,
			probabilistic,
			"test:blocks/" + blockId.substring(blockId.indexOf(':') + 1),
			true,
			Math.min(1.0, expectedDropsPerBreak),
			expectedDropsPerBreak,
			breakTicks,
			Map.of()
		);
	}

	private static BlockAcquisitionRule scoredToolRule(
		String blockId,
		String outputItemId,
		String toolItemId,
		double expectedDropsPerBreak,
		int breakTicks
	) {
		boolean probabilistic = expectedDropsPerBreak < 1.0;
		return new BlockAcquisitionRule(
			blockId,
			outputItemId,
			List.of(toolItemId),
			false,
			probabilistic,
			"test:blocks/" + blockId.substring(blockId.indexOf(':') + 1),
			true,
			Math.min(1.0, expectedDropsPerBreak),
			expectedDropsPerBreak,
			breakTicks,
			Map.of(toolItemId, breakTicks)
		);
	}

	private static void addCraftRecipeFact(
		ActionFactStore facts,
		String recipeId,
		String outputItemId,
		int outputCount,
		Map<String, Integer> inputCounts
	) {
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", recipeId),
			Map.of(
				"outputItemId", outputItemId,
				"outputCount", outputCount,
				"inputCounts", inputCounts,
				"gridKind", "PLAYER_2X2"
			),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));
	}

	private static void addSurvivalCraftFacts(ActionFactStore facts) {
		for (CraftingOpportunity craft : ActionGraphRecipeFixtures.survivalCrafts()) {
			facts.upsert(new ActionFact(
				ActionFactIdentity.craftRecipe("world-a", "bot", craft.recipeId()),
				craftPayload(craft),
				ActionFactProvenance.INFERRED,
				90,
				ActionFact.NEVER_STALE
			));
		}
	}

	private static Map<String, Object> craftPayload(CraftingOpportunity craft) {
		return Map.of(
			"outputItemId", craft.outputItemId(),
			"outputCount", craft.outputCount(),
			"inputItemIds", craft.inputItemIds(),
			"inputCounts", inputCounts(craft.inputItemIds()),
			"gridKind", craft.gridKind().name()
		);
	}

	private static void addSurvivalSmeltFacts(ActionFactStore facts) {
		for (SmeltingRecipeKnowledge recipe : ActionGraphRecipeFixtures.survivalSmelts()) {
			facts.upsert(new ActionFact(
				ActionFactIdentity.smeltRecipe("world-a", "bot", recipe.optionId()),
				Map.of(
					"inputItemId", recipe.inputItemId(),
					"outputItemId", recipe.outputItemId(),
					"outputCount", recipe.outputCount(),
					"maxInputQuantity", recipe.maxInputQuantity(),
					"cookTimeTicks", recipe.cookTimeTicks(),
					"stationItemId", recipe.stationItemId(),
					"stationItemCount", recipe.stationItemCount()
				),
				ActionFactProvenance.INFERRED,
				90,
				ActionFact.NEVER_STALE
			));
		}
	}

	private static Map<String, Integer> inputCounts(List<String> itemIds) {
		LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
		for (String itemId : itemIds) {
			counts.merge(itemId, 1, Integer::sum);
		}
		return Map.copyOf(counts);
	}

	private static void assertTrace(List<ActionTraceEvent> trace, String eventType, String actionId, String alternativeId) {
		long matches = trace.stream()
			.filter(event -> eventType.equals(event.eventType()))
			.filter(event -> actionId == null || actionId.equals(event.actionId()))
			.filter(event -> alternativeId == null || alternativeId.equals(event.alternativeId()))
			.count();
		assertTrue(matches > 0, () -> trace.toString());
	}

	private static void assertTracePayload(
		List<ActionTraceEvent> trace,
		String eventType,
		String actionId,
		String payloadKey,
		Object payloadValue
	) {
		long matches = trace.stream()
			.filter(event -> eventType.equals(event.eventType()))
			.filter(event -> actionId == null || actionId.equals(event.actionId()))
			.filter(event -> payloadValue.equals(event.payload().get(payloadKey)))
			.count();
		assertTrue(matches > 0, () -> trace.toString());
	}
}
