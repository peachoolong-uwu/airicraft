package ai.moeru.airicraft.agent.integration.map;

import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.agent.llm.LlmImageAttachment;
import ai.moeru.airicraft.agent.llm.PlannerProviderToolResult;
import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import ai.moeru.airicraft.agent.llm.PlannerToolCatalog;
import ai.moeru.airicraft.agent.llm.PlannerToolProvider;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

public final class MapPlannerToolProvider implements PlannerToolProvider {
	public static final String TAKE_MAP_LOOK = "take_map_look";

	private final Supplier<MapIntegrationRegistry> registrySupplier;

	public MapPlannerToolProvider(Supplier<MapIntegrationRegistry> registrySupplier) {
		this.registrySupplier = Objects.requireNonNull(registrySupplier, "registrySupplier");
	}

	@Override
	public String id() {
		return "map_integration";
	}

	@Override
	public boolean available() {
		return registry().preferred().isPresent();
	}

	@Override
	public List<Map<String, Object>> openAiTools() {
		if (!available()) {
			return List.of();
		}
		return List.of(
			PlannerToolCatalog.toolForProvider(
				TAKE_MAP_LOOK,
				"Attach a stable internal map image from the active map provider, independent of the current on-screen HUD size.",
				PlannerToolCatalog.propertiesForProvider(
					PlannerToolCatalog.propForProvider("provider", PlannerToolCatalog.optionalStringForProvider("Optional map provider id.")),
					PlannerToolCatalog.propForProvider("kind", PlannerToolCatalog.enumStringForProvider("Map image kind.", List.of("worldmap", "minimap"))),
					PlannerToolCatalog.propForProvider("dimension", PlannerToolCatalog.optionalStringForProvider("Dimension id.")),
					PlannerToolCatalog.propForProvider("radiusChunks", Map.of("type", "integer", "description", "Optional map radius in chunks.")),
					PlannerToolCatalog.propForProvider("zoom", Map.of("type", "integer", "description", "Optional map zoom level.")),
					PlannerToolCatalog.propForProvider("grid", Map.of("type", "boolean", "description", "Whether to include a chunk grid overlay.")),
					PlannerToolCatalog.propForProvider("originX", Map.of("type", "integer", "description", "Optional map center block X.")),
					PlannerToolCatalog.propForProvider("originZ", Map.of("type", "integer", "description", "Optional map center block Z."))
				),
				List.of()
			)
		);
	}

	@Override
	public String promptInstructions() {
		if (!available()) {
			return "";
		}
		return """
			Use remember_place, recall_place, list_places, and forget_place for all location memory, including JourneyMap waypoints.
			take_map_look attaches a stable internal minimap/worldmap image to the follow-up. Use it for minimap or map questions instead of take_a_look; it is not a capture of the current on-screen HUD.
			Pass originX and originZ when the map should be centered somewhere other than the player's current position.
			""";
	}

	@Override
	public boolean handles(String toolName) {
		String normalized = PlannerToolCatalog.normalizeName(toolName);
		return TAKE_MAP_LOOK.equals(normalized);
	}

	@Override
	public boolean isReadTool(String toolName) {
		String normalized = PlannerToolCatalog.normalizeName(toolName);
		return TAKE_MAP_LOOK.equals(normalized);
	}

	@Override
	public void validateArguments(String toolName, JsonObject arguments) {

	}

	@Override
	public CompletableFuture<String> execute(PlannerToolCall toolCall) {
		return executeResult(toolCall).thenApply(PlannerProviderToolResult::text);
	}

	@Override
	public CompletableFuture<PlannerProviderToolResult> executeResult(PlannerToolCall toolCall) {
		String name = PlannerToolCatalog.normalizeName(toolCall == null ? null : toolCall.name());
		JsonObject args = toolCall == null ? null : toolCall.arguments();
		return switch (name) {
			case TAKE_MAP_LOOK -> takeMapLook(args);
			default -> CompletableFuture.completedFuture(PlannerProviderToolResult.text("MAP_UNAVAILABLE: unknown_map_tool"));
		};
	}

	private CompletableFuture<PlannerProviderToolResult> takeMapLook(JsonObject args) {
		MapIntegrationProvider provider;
		try {
			provider = provider(stringArg(args, "provider"));
		}
		catch (RuntimeException exception) {
			return CompletableFuture.completedFuture(PlannerProviderToolResult.text(mapFailureText(exception)));
		}
		return provider.captureMap(new MapImageRequest(
			provider.id(),
			stringArg(args, "kind", "worldmap"),
			stringArg(args, "dimension"),
			intArg(args, "radiusChunks", 8),
			intArg(args, "zoom", 0),
			booleanArg(args, "grid", false),
			nullableIntArg(args, "originX"),
			nullableIntArg(args, "originZ")
		)).handle((capture, throwable) -> {
			if (throwable != null) {
				return PlannerProviderToolResult.text(mapFailureText(throwable));
			}
			return PlannerProviderToolResult.image(
				"Tool result for take_map_look: provider=" + provider.id()
					+ ", kind=" + capture.kind()
					+ ", image attached.",
				new LlmImageAttachment("image/" + capture.format().toLowerCase(java.util.Locale.ROOT), capture.imageBytes(), "auto")
			);
		});
	}

	private MapIntegrationProvider provider(String providerId) {
		MapIntegrationRegistry registry = registry();
		if (providerId != null && !providerId.isBlank()) {
			return registry.provider(providerId)
				.filter(MapIntegrationProvider::available)
				.orElseThrow(() -> new IllegalStateException("Map provider unavailable: " + providerId));
		}
		return registry.preferred().orElseThrow(() -> new IllegalStateException("No map provider is available"));
	}

	private MapIntegrationRegistry registry() {
		MapIntegrationRegistry registry = registrySupplier.get();
		return registry == null ? MapIntegrationRegistry.empty() : registry;
	}

	private static String stringArg(JsonObject args, String key) {
		return stringArg(args, key, null);
	}

	private static String stringArg(JsonObject args, String key, String fallback) {
		if (args == null || !args.has(key) || !args.get(key).isJsonPrimitive()) {
			return fallback;
		}
		return args.get(key).getAsString();
	}

	private static int intArg(JsonObject args, String key, int fallback) {
		if (args == null || !args.has(key) || !args.get(key).isJsonPrimitive()) {
			return fallback;
		}
		return args.get(key).getAsInt();
	}

	private static boolean booleanArg(JsonObject args, String key, boolean fallback) {
		if (args == null || !args.has(key) || !args.get(key).isJsonPrimitive()) {
			return fallback;
		}
		return args.get(key).getAsBoolean();
	}

	private static Integer nullableIntArg(JsonObject args, String key) {
		if (args == null || !args.has(key) || !args.get(key).isJsonPrimitive()) {
			return null;
		}
		return args.get(key).getAsInt();
	}

	private static String mapFailureText(Throwable throwable) {
		Throwable cause = throwable instanceof CompletionException completionException && completionException.getCause() != null
			? completionException.getCause()
			: throwable;
		if (cause instanceof BridgeUnavailableException bridgeUnavailableException) {
			String message = bridgeUnavailableException.getMessage();
			return "MAP_UNAVAILABLE: " + bridgeUnavailableException.code()
				+ (message == null || message.isBlank() ? "" : " - " + message);
		}
		return "MAP_UNAVAILABLE: map_capture_failed";
	}
}
