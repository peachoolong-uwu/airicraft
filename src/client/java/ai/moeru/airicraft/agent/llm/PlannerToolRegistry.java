package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class PlannerToolRegistry {
	private final List<PlannerToolProvider> providers;
	private final PlannerToolSurface toolSurface = new PlannerToolSurface();
	private List<Map<String, Object>> fixedTools;
	private String fixedInstructions;

	/** Freeze the advertised schema for one model session; action gates still apply. */
	public void freezeToolPrefix() {
		fixedTools = availableOpenAiTools();
		fixedInstructions = providers.stream().filter(PlannerToolProvider::available)
			.map(PlannerToolProvider::promptInstructions).filter(value -> !value.isBlank())
			.collect(Collectors.joining("\n"));
	}

	public boolean hasFixedPrefix() { return fixedTools != null; }

	public boolean endsTurn(String name) {
		return providers.stream().anyMatch(provider -> provider.handles(name) && provider.endsTurn(name));
	}

	public String contextSnapshot() {
		return providers.stream().filter(PlannerToolProvider::available)
			.map(PlannerToolProvider::contextSnapshot).filter(value -> !value.isBlank())
			.collect(Collectors.joining("\n"));
	}

	private PlannerToolRegistry(List<PlannerToolProvider> providers) {
		this.providers = List.copyOf(providers);
	}

	public static PlannerToolRegistry empty() {
		return new PlannerToolRegistry(List.of());
	}

	public static PlannerToolRegistry of(PlannerToolProvider... providers) {
		if (providers == null || providers.length == 0) {
			return empty();
		}
		return new PlannerToolRegistry(Arrays.stream(providers)
			.filter(Objects::nonNull)
			.toList());
	}

	public List<Map<String, Object>> openAiTools() {
		return fixedTools == null ? filterToActiveSurface(availableOpenAiTools()) : fixedTools;
	}

	public Optional<Map<String, Object>> activeOpenAiTool(String toolName) {
		String normalized = PlannerToolCatalog.normalizeName(toolName);
		return openAiTools().stream()
			.filter(tool -> normalized.equals(PlannerToolCatalog.normalizeName(toolName(tool))))
			.findFirst();
	}

	public List<Map<String, Object>> allAvailableOpenAiTools() {
		return availableOpenAiTools();
	}

	public List<String> activeToolNames() {
		return fixedTools == null ? toolSurface.activeToolNames() : availableToolNames(fixedTools);
	}

	public boolean isActiveTool(String toolName) {
		String normalized = PlannerToolCatalog.normalizeName(toolName);
		return fixedTools == null
			? toolSurface.isActive(normalized) && availableToolNames(availableOpenAiTools()).contains(normalized)
			: availableToolNames(fixedTools).contains(normalized);
	}

	public PlannerToolSurface.DiscoveryResult discoverTools(String query, int maxResults) {
		return toolSurface.discover(availableToolDescriptors(), query, maxResults);
	}

	public void resetToolSurface() {
		toolSurface.reset();
	}

	public void setSafetyHoldActive(boolean active) {
		toolSurface.setSafetyHoldActive(active);
	}

	/**
	 * Keeps legacy mock-response tests independent of the staged production surface.
	 */
	void activateAllForTesting() {
		toolSurface.activateAllForTesting(availableToolDescriptors());
	}

	private List<Map<String, Object>> availableOpenAiTools() {
		ArrayList<Map<String, Object>> tools = new ArrayList<>(PlannerToolCatalog.openAiTools());
		for (PlannerToolProvider provider : providers) {
			if (provider.available()) {
				tools.addAll(provider.openAiTools());
			}
		}
		return List.copyOf(tools);
	}

	private List<Map<String, Object>> filterToActiveSurface(List<Map<String, Object>> tools) {
		return tools.stream()
			.filter(tool -> toolSurface.isActive(toolName(tool)))
			.toList();
	}

	public String promptInstructions() {
		if (fixedInstructions != null) return fixedInstructions;
		return providers.stream()
			.filter(provider -> provider.available() && providerHasActiveTool(provider))
			.map(PlannerToolProvider::promptInstructions)
			.filter(instruction -> instruction != null && !instruction.isBlank())
			.collect(Collectors.joining("\n"));
	}

	public String availableToolNames() {
		return availableToolNames(openAiTools()).stream()
			.collect(Collectors.joining(", "));
	}

	private static List<String> availableToolNames(Collection<Map<String, Object>> tools) {
		return tools.stream()
			.map(PlannerToolRegistry::toolName)
			.filter(name -> !name.isBlank())
			.map(PlannerToolCatalog::normalizeName)
			.toList();
	}

	private boolean providerHasActiveTool(PlannerToolProvider provider) {
		return provider.openAiTools().stream().anyMatch(tool -> toolSurface.isActive(toolName(tool)));
	}

	private List<PlannerToolSurface.ToolDescriptor> availableToolDescriptors() {
		return availableOpenAiTools().stream()
			.map(tool -> new PlannerToolSurface.ToolDescriptor(
				toolName(tool),
				toolDescription(tool),
				PlannerToolSurface.categoryFor(toolName(tool))
			))
			.filter(descriptor -> !descriptor.name().isBlank())
			.toList();
	}

	public boolean isKnownTool(String toolName) {
		return executionMetadata(toolName).isPresent();
	}

	public boolean isReadTool(String toolName) {
		return executionMetadata(toolName)
			.map(ToolExecutionMetadata::readOnly)
			.orElse(false);
	}

	public boolean isBatchSafeReadTool(String toolName) {
		return executionMetadata(toolName)
			.map(ToolExecutionMetadata::batchSafeRead)
			.orElse(false);
	}

	public ReadOnlyBatchAuthorization authorizeReadOnlyBatch(List<PlannerToolCall> toolCalls) {
		if (toolCalls == null || toolCalls.isEmpty()) {
			return ReadOnlyBatchAuthorization.reject(BatchRejectionReason.UNKNOWN_TOOL, "");
		}
		for (PlannerToolCall toolCall : toolCalls) {
			String toolName = PlannerToolCatalog.normalizeName(toolCall == null ? null : toolCall.name());
			Optional<ToolExecutionMetadata> metadata = executionMetadata(toolName);
			if (metadata.isEmpty()) {
				return ReadOnlyBatchAuthorization.reject(BatchRejectionReason.UNKNOWN_TOOL, toolName);
			}
			if (!metadata.get().readOnly() || !metadata.get().batchSafeRead()) {
				return ReadOnlyBatchAuthorization.reject(BatchRejectionReason.NOT_BATCH_SAFE, toolName);
			}
		}
		return ReadOnlyBatchAuthorization.allow();
	}

	public Optional<ToolExecutionMetadata> executionMetadata(String toolName) {
		String normalized = PlannerToolCatalog.normalizeName(toolName);
		if (PlannerToolCatalog.isKnownTool(normalized)) {
			return Optional.of(new ToolExecutionMetadata(
				normalized,
				true,
				PlannerToolCatalog.isReadTool(normalized),
				PlannerToolCatalog.isBatchSafeReadTool(normalized)
			));
		}
		return providerFor(normalized)
			.map(provider -> new ToolExecutionMetadata(
				normalized,
				true,
				provider.isReadTool(normalized),
				provider.isBatchSafeReadTool(normalized)
			));
	}

	public Optional<PlannerToolProvider> providerFor(String toolName) {
		String normalized = PlannerToolCatalog.normalizeName(toolName);
		return providers.stream()
			.filter(provider -> provider.handles(normalized))
			.findFirst();
	}

	public void validateProviderArguments(String toolName, JsonObject arguments) {
		providerFor(toolName)
			.orElseThrow(() -> new com.google.gson.JsonParseException("Unknown planner tool: " + toolName))
			.validateArguments(toolName, arguments);
	}

	public CompletableFuture<String> execute(PlannerToolCall toolCall) {
		return providerFor(toolCall == null ? null : toolCall.name())
			.orElseThrow(() -> new IllegalArgumentException("No provider for tool: " + (toolCall == null ? "null" : toolCall.name())))
			.execute(toolCall);
	}

	private static String toolName(Map<String, Object> tool) {
		Object function = tool == null ? null : tool.get("function");
		if (!(function instanceof Map<?, ?> functionMap)) {
			return "";
		}
		Object name = functionMap.get("name");
		return name instanceof String string ? string : "";
	}

	private static String toolDescription(Map<String, Object> tool) {
		Object function = tool == null ? null : tool.get("function");
		if (!(function instanceof Map<?, ?> functionMap)) {
			return "";
		}
		Object description = functionMap.get("description");
		return description instanceof String string ? string : "";
	}

	public enum BatchRejectionReason {
		UNKNOWN_TOOL,
		NOT_BATCH_SAFE
	}

	public record ToolExecutionMetadata(
		String name,
		boolean registered,
		boolean readOnly,
		boolean batchSafeRead
	) {
		public ToolExecutionMetadata {
			name = PlannerToolCatalog.normalizeName(name);
		}
	}

	public record ReadOnlyBatchAuthorization(
		boolean authorized,
		BatchRejectionReason rejectionReason,
		String toolName
	) {
		public ReadOnlyBatchAuthorization {
			toolName = PlannerToolCatalog.normalizeName(toolName);
		}

		private static ReadOnlyBatchAuthorization allow() {
			return new ReadOnlyBatchAuthorization(true, null, "");
		}

		private static ReadOnlyBatchAuthorization reject(BatchRejectionReason reason, String toolName) {
			return new ReadOnlyBatchAuthorization(false, reason, toolName);
		}
	}
}
