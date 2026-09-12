package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.tasks.NearbyEntityService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static ai.moeru.airicraft.agent.llm.CurrentInventoryService.CraftingTableAccess.CAN_CRAFT;
import static ai.moeru.airicraft.agent.llm.CurrentInventoryService.CraftingTableAccess.IN_INVENTORY;
import static ai.moeru.airicraft.agent.llm.CurrentInventoryService.CraftingTableAccess.MISSING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentInventoryServiceTest {
	@Test
	void ingredientPermutationsDoNotCrowdOutDistinctCraftableRecipes() {
		var duplicate = craft("sticks", "minecraft:stick");
		var recipes = new java.util.ArrayList<>(java.util.Collections.nCopies(30, duplicate));
		recipes.add(craft("mixed_planks_to_barrel", "minecraft:barrel"));
		String result = CurrentInventoryService.formatCraftables(recipes, CAN_CRAFT, null);
		assertTrue(result.contains("mixed_planks_to_barrel"));
		assertTrue(result.contains("matchedRecipes=2, returnedRecipes=2, truncated=false"));
	}

	@Test
	void outputFilterRunsBeforeTheLimitAndOmittedResultsAreExplicit() {
		var recipes = new java.util.ArrayList<ai.moeru.airicraft.agent.tasks.CraftingOpportunity>();
		for (int i = 0; i < 25; i++) recipes.add(craft("recipe_" + i, "minecraft:item_" + i));
		recipes.add(craft("mixed_planks_to_barrel", "minecraft:barrel"));
		String all = CurrentInventoryService.formatCraftables(recipes, CAN_CRAFT, null);
		assertTrue(all.contains("matchedRecipes=26, returnedRecipes=24, truncated=true"));
		String filtered = CurrentInventoryService.formatCraftables(recipes, CAN_CRAFT, "minecraft:barrel");
		assertTrue(filtered.contains("mixed_planks_to_barrel"));
		assertTrue(filtered.contains("matchedRecipes=1, returnedRecipes=1, truncated=false"));
		assertTrue(CurrentInventoryService.formatCraftables(recipes, CAN_CRAFT, "minecraft:diamond")
			.contains("matchedRecipes=0, returnedRecipes=0, truncated=false"));
	}

	private static ai.moeru.airicraft.agent.tasks.CraftingOpportunity craft(String recipe, String output) {
		return new ai.moeru.airicraft.agent.tasks.CraftingOpportunity(recipe, output, 1,
			List.of("minecraft:oak_planks"), ai.moeru.airicraft.agent.tasks.CraftingGridKind.WORKBENCH_3X3);
	}

	@Test
	void unusableNearbyTableWithoutPortableSetupCountsAsMissing() {
		assertEquals(
			MISSING,
			CurrentInventoryService.craftingTableAccess(false, false, Map.of())
		);
	}

	@Test
	void portableSetupStillCountsExecutableWithoutUsableNearbyTable() {
		assertEquals(
			IN_INVENTORY,
			CurrentInventoryService.craftingTableAccess(false, false, Map.of("minecraft:crafting_table", 1))
		);
		assertEquals(
			CAN_CRAFT,
			CurrentInventoryService.craftingTableAccess(false, false, Map.of("minecraft:oak_planks", 4))
		);
	}

	@Test
	void formatNearbyEntitiesUsesShortestUniqueUuidTokens() {
		String formatted = CurrentInventoryService.formatNearbyEntities(List.of(
			new NearbyEntityService.NearbyEntitySnapshot(
				1,
				"12345678-aaaa-4d9d-8b9d-fb24b98cb81d",
				"Slime A",
				"minecraft:slime",
				1.0D,
				64.0D,
				1.0D,
				3.0D,
				true,
				4.0F,
				4.0F,
				false
			),
			new NearbyEntityService.NearbyEntitySnapshot(
				2,
				"12345678-bbbb-4d9d-8b9d-fb24b98cb81d",
				"Slime B",
				"minecraft:slime",
				2.0D,
				64.0D,
				2.0D,
				4.0D,
				true,
				4.0F,
				4.0F,
				false
			)
		));

		assertTrue(formatted.contains("uuid=12345678-a"));
		assertTrue(formatted.contains("uuid=12345678-b"));
	}
}
