package ai.moeru.airicraft.agent.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerPromptPolicyTest {
	@Test
	void promptUsesEvidenceWorkAndFixedCatalogContracts() {
		String prompt = PlannerPromptPolicy.systemPrompt(PlannerVisionMode.EXTERNAL_SUMMARY);

		assertTrue(prompt.startsWith("You are the planner for a Minecraft companion."));
		assertTrue(prompt.contains("Available tools: discover_tools, start_action_goal, inspect_action_goal, cancel_action_goal, clear_goal."));
		assertTrue(prompt.contains("discover_tools is catalog assistance"));
		assertTrue(prompt.contains("advertised typed schema is authoritative"));
		assertFalse(prompt.contains("navigate_to"));
		assertTrue(prompt.contains("check_position"));
		assertFalse(prompt.contains("craft_recipe"));
		assertFalse(prompt.contains("{{"));
	}

	@Test
	void compactPolicyPreservesGraphAndTerminalUpdateRules() {
		String prompt = PlannerPromptPolicy.systemPrompt(PlannerVisionMode.EXTERNAL_SUMMARY);

		assertTrue(prompt.contains("there is no graph-first or legacy-fallback requirement"));
		assertTrue(prompt.contains("INVENTORY_DELTA_AT_LEAST"));
		assertTrue(prompt.contains("Outcomes belong to their work identities"));
		assertTrue(prompt.contains("same-client admin messages"));
		assertTrue(prompt.contains("unknown_acquisition_method"));
		assertTrue(prompt.contains("does not finish the objective"));
		assertTrue(prompt.contains("minecraft:charcoal"));
		assertTrue(prompt.contains("craft minecraft:torch from charcoal and sticks"));
		assertTrue(prompt.contains("allowUnilluminated"));
		assertTrue(prompt.contains("Accepted means admitted, not completed"));
	}

	@Test
	void providerInstructionsAppearOnlyAfterProviderToolDiscovery() {
		PlannerToolRegistry registry = PlannerToolRegistry.of(
			new PromptOnlyProvider("Use search_recipes for broad recipe-viewer searches before inventing recipe ids.")
		);

		String initialPrompt = PlannerPromptPolicy.systemPrompt(PlannerVisionMode.EXTERNAL_SUMMARY, registry);
		assertFalse(initialPrompt.contains("search_recipes"));

		registry.discoverTools("recipe", 4);
		String discoveredPrompt = PlannerPromptPolicy.systemPrompt(PlannerVisionMode.EXTERNAL_SUMMARY, registry);
		assertTrue(discoveredPrompt.contains("search_recipes"));
		assertTrue(discoveredPrompt.contains("Use search_recipes for broad recipe-viewer searches"));
	}

	@Test
	void providerGuidanceArrivesWithTheDiscoveredSchema() {
		PlannerToolRegistry registry = PlannerToolRegistry.of(
			new WorldFeatureSearchToolProvider(WorldFeatureSearchTool.textOnly(ignored -> "unused"))
		);

		assertFalse(PlannerPromptPolicy.systemPrompt(PlannerVisionMode.EXTERNAL_SUMMARY, registry).contains("find_world_features"));
		registry.discoverTools("feature", 4);
		String prompt = PlannerPromptPolicy.systemPrompt(PlannerVisionMode.EXTERNAL_SUMMARY, registry);

		assertTrue(prompt.contains("find_world_features"));
		assertTrue(prompt.contains("coordinate-grounded exploration targets"));
		assertTrue(prompt.contains("featureKind=water_body"));
	}

	@Test
	void compactionInstructionLoadsMarkdownTemplate() {
		String prompt = PlannerPromptPolicy.compactionInstruction();

		assertTrue(prompt.startsWith("COMPACTION TASK:"));
		assertTrue(prompt.contains("\"forgettable_noise\": string[]"));
		assertFalse(prompt.contains("{{"));
	}

	private record PromptOnlyProvider(String promptInstructions) implements PlannerToolProvider {
		@Override
		public String id() {
			return "prompt_only";
		}

		@Override
		public java.util.List<java.util.Map<String, Object>> openAiTools() {
			return java.util.List.of(PlannerToolCatalog.toolForProvider(
				"search_recipes",
				"Search recipe-viewer recipes.",
				PlannerToolCatalog.propertiesForProvider(),
				java.util.List.of()
			));
		}

		@Override
		public boolean handles(String toolName) {
			return false;
		}

		@Override
		public java.util.concurrent.CompletableFuture<String> execute(PlannerToolCall toolCall) {
			return java.util.concurrent.CompletableFuture.completedFuture("unused");
		}
	}
}
