package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerToolRegistryTest {
	@Test
	void fixedRoleCatalogDoesNotChangeOnDiscoveryOrReflexAndOmitsOldLifecycle() {
		var registry = PlannerToolRegistry.of(new ai.moeru.airicraft.agent.work.WorkToolProvider(PlannerActionToolExecutor.DISABLED));
		registry.freezeToolPrefix();
		String before = new Gson().toJson(registry.openAiTools());
		String discovery = registry.discoverTools("resume work",8).renderToolResult();
		registry.setSafetyHoldActive(true);
		assertEquals(before,new Gson().toJson(registry.openAiTools()));
		assertTrue(registry.isActiveTool("resume_work"));
		assertFalse(registry.isActiveTool("resume_task"));
		assertFalse(registry.isActiveTool("cancel_task"));
		assertTrue(discovery.contains("does not change"));
		registry.setSafetyHoldActive(false);
		assertEquals(before,new Gson().toJson(registry.openAiTools()));
	}

	@Test
	void initialSurfaceContainsOnlyCoreToolsAndDiscoverTools() {
		PlannerToolRegistry registry = PlannerToolRegistry.empty();

		assertEquals(List.of(
			PlannerToolCatalog.DISCOVER_TOOLS,
			PlannerToolCatalog.START_ACTION_GOAL,
			PlannerToolCatalog.INSPECT_ACTION_GOAL,
			PlannerToolCatalog.CANCEL_ACTION_GOAL,
			PlannerToolCatalog.CLEAR_GOAL
		), toolNames(registry.openAiTools()));
		assertFalse(registry.isActiveTool(PlannerToolCatalog.NAVIGATE_TO));
	}

	@Test
	void initialSchemaPayloadIsMateriallySmallerThanTheFullCatalog() {
		PlannerToolRegistry registry = PlannerToolRegistry.empty();
		Gson gson = new Gson();
		int initialBytes = gson.toJson(registry.openAiTools()).getBytes(StandardCharsets.UTF_8).length;
		int fullCatalogBytes = gson.toJson(PlannerToolCatalog.openAiTools()).getBytes(StandardCharsets.UTF_8).length;

		assertTrue(initialBytes * 2 < fullCatalogBytes, () ->
			"Expected staged schemas to be less than half the full catalog: initial=" + initialBytes + " full=" + fullCatalogBytes
		);
	}

	@Test
	void externalSurfaceIncludesEveryAvailableBuiltInAndProviderTool() {
		PlannerToolRegistry registry = PlannerToolRegistry.of(new FlippingProvider());

		List<String> externalNames = toolNames(registry.allAvailableOpenAiTools());

		assertTrue(externalNames.contains(PlannerToolCatalog.NAVIGATE_TO));
		assertTrue(externalNames.contains(PlannerToolCatalog.CRAFT_RECIPE));
		assertTrue(externalNames.contains("search_recipes"));
		assertTrue(externalNames.size() > registry.openAiTools().size());
	}

	@Test
	void discoveryActivatesBoundedSpecialistSchemasAndReturnsConciseCards() {
		PlannerToolRegistry registry = PlannerToolRegistry.empty();

		PlannerToolSurface.DiscoveryResult result = registry.discoverTools("navigation", 3);

		assertFalse(result.matches().isEmpty());
		assertTrue(result.matches().size() <= 3);
		assertTrue(result.matches().stream().allMatch(match -> "navigation".equals(match.category())));
		assertTrue(result.renderToolResult().contains("cards=["));
		assertTrue(result.renderToolResult().contains("activatedTools="));
		assertTrue(result.renderToolResult().length() < 1_500);
		assertTrue(registry.isActiveTool(PlannerToolCatalog.NAVIGATE_TO));
		assertTrue(toolNames(registry.openAiTools()).contains(PlannerToolCatalog.NAVIGATE_TO));
		assertFalse(toolNames(registry.openAiTools()).contains(PlannerToolCatalog.CRAFT_RECIPE));
	}

	@Test
	void safetyHoldTemporarilyActivatesResumeControlAndResetDropsSpecialists() {
		PlannerToolRegistry registry = PlannerToolRegistry.empty();
		registry.discoverTools("navigation", 3);

		registry.setSafetyHoldActive(true);
		assertTrue(registry.isActiveTool(PlannerToolCatalog.RESUME_TASK));

		registry.setSafetyHoldActive(false);
		registry.resetToolSurface();
		assertFalse(registry.isActiveTool(PlannerToolCatalog.RESUME_TASK));
		assertFalse(registry.isActiveTool(PlannerToolCatalog.NAVIGATE_TO));
		assertEquals(5, registry.openAiTools().size());
	}

	@Test
	void routesDiscoveredProviderToolAfterAvailabilityFlipsUnavailable() {
		FlippingProvider provider = new FlippingProvider();
		PlannerToolRegistry registry = PlannerToolRegistry.of(provider);

		assertFalse(toolNames(registry.openAiTools()).contains("search_recipes"));
		registry.discoverTools("recipe", 4);
		assertTrue(toolNames(registry.openAiTools()).contains("search_recipes"));

		provider.available.set(false);
		JsonObject arguments = new JsonObject();
		arguments.addProperty("query", "oak planks");

		assertTrue(registry.isKnownTool("search_recipes"));
		assertTrue(registry.isReadTool("search_recipes"));
		assertDoesNotThrow(() -> registry.validateProviderArguments("search_recipes", arguments));
		assertEquals("RECIPES_UNAVAILABLE: provider_reloading", registry.execute(new PlannerToolCall(
			"call_search",
			"search_recipes",
			arguments,
			null,
			null
		)).join());
	}

	@Test
	void authorizesRegisteredBuiltInAndProviderReadTools() {
		PlannerToolRegistry registry = PlannerToolRegistry.of(new FlippingProvider());

		PlannerToolRegistry.ReadOnlyBatchAuthorization authorization = registry.authorizeReadOnlyBatch(List.of(
			call(PlannerToolCatalog.INSPECT_INVENTORY),
			call("search_recipes")
		));

		assertTrue(authorization.authorized());
		assertNull(authorization.rejectionReason());
	}

	@Test
	void discoveryRemainsAReadToolButIsNotSafeForParallelBatching() {
		PlannerToolRegistry registry = PlannerToolRegistry.empty();

		assertTrue(registry.isReadTool(PlannerToolCatalog.DISCOVER_TOOLS));
		assertFalse(registry.isBatchSafeReadTool(PlannerToolCatalog.DISCOVER_TOOLS));

		PlannerToolRegistry.ReadOnlyBatchAuthorization authorization = registry.authorizeReadOnlyBatch(List.of(
			call(PlannerToolCatalog.DISCOVER_TOOLS)
		));

		assertFalse(authorization.authorized());
		assertEquals(PlannerToolRegistry.BatchRejectionReason.NOT_BATCH_SAFE, authorization.rejectionReason());
		assertEquals(PlannerToolCatalog.DISCOVER_TOOLS, authorization.toolName());
	}

	@Test
	void rejectsDiscoveryMixedWithAReadTool() {
		PlannerToolRegistry registry = PlannerToolRegistry.empty();

		PlannerToolRegistry.ReadOnlyBatchAuthorization authorization = registry.authorizeReadOnlyBatch(List.of(
			call(PlannerToolCatalog.DISCOVER_TOOLS),
			call(PlannerToolCatalog.INSPECT_INVENTORY)
		));

		assertFalse(authorization.authorized());
		assertEquals(PlannerToolRegistry.BatchRejectionReason.NOT_BATCH_SAFE, authorization.rejectionReason());
		assertEquals(PlannerToolCatalog.DISCOVER_TOOLS, authorization.toolName());
	}

	@Test
	void rejectsMixedReadWriteAndUnknownBatches() {
		PlannerToolRegistry registry = PlannerToolRegistry.of(new FlippingProvider());

		PlannerToolRegistry.ReadOnlyBatchAuthorization mixed = registry.authorizeReadOnlyBatch(List.of(
			call(PlannerToolCatalog.INSPECT_INVENTORY),
			call(PlannerToolCatalog.CRAFT_RECIPE)
		));
		PlannerToolRegistry.ReadOnlyBatchAuthorization unknown = registry.authorizeReadOnlyBatch(List.of(
			call(PlannerToolCatalog.INSPECT_INVENTORY),
			call("missing_tool")
		));

		assertFalse(mixed.authorized());
		assertEquals(PlannerToolRegistry.BatchRejectionReason.NOT_BATCH_SAFE, mixed.rejectionReason());
		assertEquals(PlannerToolCatalog.CRAFT_RECIPE, mixed.toolName());
		assertFalse(unknown.authorized());
		assertEquals(PlannerToolRegistry.BatchRejectionReason.UNKNOWN_TOOL, unknown.rejectionReason());
		assertEquals("missing_tool", unknown.toolName());
	}

	@Test
	void authorizationTracksRegisteredToolChanges() {
		PlannerToolCall providerRead = call("search_recipes");
		PlannerToolRegistry absent = PlannerToolRegistry.empty();
		PlannerToolRegistry registered = PlannerToolRegistry.of(new FlippingProvider());

		assertEquals(
			PlannerToolRegistry.BatchRejectionReason.UNKNOWN_TOOL,
			absent.authorizeReadOnlyBatch(List.of(providerRead)).rejectionReason()
		);
		assertTrue(registered.authorizeReadOnlyBatch(List.of(providerRead)).authorized());
	}

	@Test
	void authorizationTracksProviderBatchMetadataChanges() {
		FlippingProvider provider = new FlippingProvider();
		PlannerToolRegistry registry = PlannerToolRegistry.of(provider);
		PlannerToolCall providerRead = call("search_recipes");

		assertTrue(registry.executionMetadata("search_recipes").orElseThrow().batchSafeRead());
		assertTrue(registry.authorizeReadOnlyBatch(List.of(providerRead)).authorized());

		provider.batchSafe.set(false);

		assertFalse(registry.executionMetadata("search_recipes").orElseThrow().batchSafeRead());
		PlannerToolRegistry.ReadOnlyBatchAuthorization authorization = registry.authorizeReadOnlyBatch(List.of(providerRead));
		assertFalse(authorization.authorized());
		assertEquals(PlannerToolRegistry.BatchRejectionReason.NOT_BATCH_SAFE, authorization.rejectionReason());
	}

	private static PlannerToolCall call(String name) {
		return new PlannerToolCall("call-" + name, name, new JsonObject(), null, null);
	}

	private static List<String> toolNames(List<Map<String, Object>> tools) {
		return tools.stream()
			.map(PlannerToolRegistryTest::toolName)
			.toList();
	}

	private static String toolName(Map<String, Object> tool) {
		@SuppressWarnings("unchecked")
		Map<String, Object> function = (Map<String, Object>) tool.get("function");
		return (String) function.get("name");
	}

	private static final class FlippingProvider implements PlannerToolProvider {
		private final AtomicBoolean available = new AtomicBoolean(true);
		private final AtomicBoolean batchSafe = new AtomicBoolean(true);

		@Override
		public String id() {
			return "flipping_recipe_search";
		}

		@Override
		public boolean available() {
			return available.get();
		}

		@Override
		public boolean isBatchSafeReadTool(String toolName) {
			return batchSafe.get() && isReadTool(toolName);
		}

		@Override
		public List<Map<String, Object>> openAiTools() {
			return List.of(PlannerToolCatalog.toolForProvider(
				"search_recipes",
				"Search recipes through a provider.",
				PlannerToolCatalog.propertiesForProvider(
					PlannerToolCatalog.propForProvider("query", PlannerToolCatalog.stringForProvider("Item query."))
				),
				List.of("query")
			));
		}

		@Override
		public boolean handles(String toolName) {
			return "search_recipes".equals(PlannerToolCatalog.normalizeName(toolName));
		}

		@Override
		public void validateArguments(String toolName, JsonObject arguments) {
			if (arguments == null || !arguments.has("query")) {
				throw new com.google.gson.JsonParseException("missing query");
			}
		}

		@Override
		public CompletableFuture<String> execute(PlannerToolCall toolCall) {
			if (!available()) {
				return CompletableFuture.completedFuture("RECIPES_UNAVAILABLE: provider_reloading");
			}
			return CompletableFuture.completedFuture("Tool result for search_recipes: ok");
		}
	}
}
