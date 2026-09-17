package ai.moeru.airicraft.agent.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The session-scoped subset of planner tools whose complete schemas are currently advertised.
 * Tool discovery deliberately activates only a bounded number of specialist tools so a long
 * Minecraft session cannot silently grow back into the all-tools prompt.
 */
public final class PlannerToolSurface {
	private static final int MAX_DISCOVERED_TOOL_COUNT = 8;
	private static List<String> coreToolNames() { return List.of(
		PlannerToolCatalog.DISCOVER_TOOLS,
		PlannerToolCatalog.START_ACTION_GOAL,
		PlannerToolCatalog.INSPECT_ACTION_GOAL,
		PlannerToolCatalog.CANCEL_ACTION_GOAL,
		PlannerToolCatalog.CLEAR_GOAL,
		"set_planner_goal", "change_planner_goal", "finish_planner_goal", "inspect_planner_goal", "something_wrong"
	); }

	private final LinkedHashSet<String> activeToolNames = new LinkedHashSet<>(coreToolNames());

	public List<String> activeToolNames() {
		return List.copyOf(activeToolNames);
	}

	public boolean isActive(String toolName) {
		return activeToolNames.contains(PlannerToolCatalog.normalizeName(toolName));
	}

	public void reset() {
		activeToolNames.clear();
		activeToolNames.addAll(coreToolNames());
	}

	public void setSafetyHoldActive(boolean active) {
		if (active) {
			activeToolNames.add(PlannerToolCatalog.RESUME_TASK);
		}
		else {
			activeToolNames.remove(PlannerToolCatalog.RESUME_TASK);
		}
	}

	/**
	 * Keeps legacy mock-response tests independent of the staged production surface.
	 */
	void activateAllForTesting(List<ToolDescriptor> availableTools) {
		if (availableTools == null) {
			return;
		}
		for (ToolDescriptor tool : availableTools) {
			if (tool != null && !tool.name().isBlank()) {
				activeToolNames.add(tool.name());
			}
		}
	}

	public DiscoveryResult discover(List<ToolDescriptor> availableTools, String query, int maxResults) {
		String normalizedQuery = normalizeQuery(query);
		int effectiveMaxResults = Math.clamp(maxResults, 1, 5);
		List<String> queryTerms = queryTerms(normalizedQuery);
		List<ToolDescriptor> matches = (availableTools == null ? List.<ToolDescriptor>of() : availableTools).stream()
			.filter(descriptor -> descriptor != null && !isCoreTool(descriptor.name()))
			.map(descriptor -> new RankedTool(descriptor, matchScore(descriptor, queryTerms)))
			.filter(ranked -> ranked.score() > 0)
			.sorted(Comparator.comparingInt(RankedTool::score).reversed().thenComparing(ranked -> ranked.tool().name()))
			.limit(effectiveMaxResults)
			.map(RankedTool::tool)
			.toList();

		for (ToolDescriptor match : matches) {
			activate(match.name());
		}
		return new DiscoveryResult(normalizedQuery, matches, activeToolNames());
	}

	private void activate(String toolName) {
		String normalizedName = PlannerToolCatalog.normalizeName(toolName);
		if (normalizedName.isBlank() || isCoreTool(normalizedName) || PlannerToolCatalog.RESUME_TASK.equals(normalizedName)) {
			return;
		}
		activeToolNames.remove(normalizedName);
		activeToolNames.add(normalizedName);
		while (discoveredToolCount() > MAX_DISCOVERED_TOOL_COUNT) {
			String oldest = activeToolNames.stream()
				.filter(name -> !isCoreTool(name) && !PlannerToolCatalog.RESUME_TASK.equals(name))
				.findFirst()
				.orElse(null);
			if (oldest == null) {
				return;
			}
			activeToolNames.remove(oldest);
		}
	}

	private int discoveredToolCount() {
		return (int) activeToolNames.stream()
			.filter(name -> !isCoreTool(name) && !PlannerToolCatalog.RESUME_TASK.equals(name))
			.count();
	}

	private static boolean isCoreTool(String toolName) {
		return coreToolNames().contains(PlannerToolCatalog.normalizeName(toolName));
	}

	private static String normalizeQuery(String query) {
		String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		if (normalized.isBlank()) {
			throw new IllegalArgumentException("discover_tools query is required");
		}
		return normalized;
	}

	private static List<String> queryTerms(String query) {
		LinkedHashSet<String> terms = new LinkedHashSet<>();
		for (String term : query.split("[^a-z0-9_]+")) {
			if (term.length() >= 3) {
				terms.add(term);
			}
		}
		if (terms.isEmpty()) {
			terms.add(query);
		}
		return List.copyOf(terms);
	}

	private static int matchScore(ToolDescriptor descriptor, List<String> queryTerms) {
		String name = PlannerToolCatalog.normalizeName(descriptor.name());
		String description = descriptor.description().toLowerCase(Locale.ROOT);
		String category = descriptor.category().toLowerCase(Locale.ROOT);
		int score = 0;
		for (String term : queryTerms) {
			if (category.contains(term) || term.contains(category)) {
				score += 8;
			}
			if (name.contains(term)) {
				score += 6;
			}
			if (description.contains(term)) {
				score += 2;
			}
		}
		return score;
	}

	public static String categoryFor(String toolName) {
		return switch (PlannerToolCatalog.normalizeName(toolName)) {
			case "remember_place", "recall_place", "list_places", "forget_place" -> "place memory";
			case PlannerToolCatalog.TAKE_A_LOOK,
				PlannerToolCatalog.INSPECT_WORLD,
				PlannerToolCatalog.INSPECT_INVENTORY,
				PlannerToolCatalog.INSPECT_NEARBY_ENTITIES -> "observation";
			case PlannerToolCatalog.FOLLOW_PLAYER,
				PlannerToolCatalog.NAVIGATE_TO,
				PlannerToolCatalog.RETURN_TO_SURFACE -> "navigation";
			case PlannerToolCatalog.MINE_BLOCKS,
				PlannerToolCatalog.ENSURE_BLOCKS_IN_INVENTORY,
				PlannerToolCatalog.COLLECT_RESOURCE -> "resource gathering";
			case PlannerToolCatalog.CHECK_CRAFTABLES,
				PlannerToolCatalog.CRAFT_RECIPE -> "crafting";
			case PlannerToolCatalog.CHECK_SMELTABLES,
				PlannerToolCatalog.INSPECT_SMELTING,
				PlannerToolCatalog.SMELT_ITEMS,
				PlannerToolCatalog.COLLECT_SMELTED_ITEMS,
				PlannerToolCatalog.CANCEL_SMELTING -> "smelting";
			case PlannerToolCatalog.DROP_ITEMS,
				PlannerToolCatalog.GIVE_PLAYER,
				PlannerToolCatalog.EQUIP_ITEM,
				PlannerToolCatalog.EAT_FOOD,
				PlannerToolCatalog.ATTACK_ENTITY,
				PlannerToolCatalog.USE_ENTITY,
				PlannerToolCatalog.PLACE_BLOCK,
				PlannerToolCatalog.USE_BLOCK,
				PlannerToolCatalog.BREAK_BLOCKS -> "precise interaction";
			case PlannerToolCatalog.INSPECT_ACTION_TRACE,
				PlannerToolCatalog.LIST_ACTION_CAPABILITIES,
				PlannerToolCatalog.CANCEL_TASK,
				PlannerToolCatalog.UPDATE_EVENT_POLICY,
				PlannerToolCatalog.CONFIGURE_REFLEX,
				PlannerToolCatalog.CONFIGURE_PATHFIND, PlannerToolCatalog.CONFIGURE_LIGHTING -> "advanced control";
			default -> "integration";
		};
	}

	public record ToolDescriptor(String name, String description, String category) {
		public ToolDescriptor {
			name = PlannerToolCatalog.normalizeName(name);
			description = Objects.requireNonNullElse(description, "").trim();
			category = Objects.requireNonNullElse(category, "integration").trim();
		}
	}

	public record DiscoveryResult(String query, List<ToolDescriptor> matches, List<String> activeToolNames, boolean catalogOnly) {
		public DiscoveryResult(String query, List<ToolDescriptor> matches, List<String> activeToolNames) {
			this(query, matches, activeToolNames, false);
		}
		public DiscoveryResult {
			query = Objects.requireNonNullElse(query, "");
			matches = matches == null ? List.of() : List.copyOf(matches);
			activeToolNames = activeToolNames == null ? List.of() : List.copyOf(activeToolNames);
		}

		public String renderToolResult() {
			if (matches.isEmpty()) {
				return "Tool result for discover_tools: query=" + query
					+ " matched=0. Try a capability such as observation, navigation, crafting, smelting, resource gathering, precise interaction, advanced control, or integration.";
			}
			ArrayList<String> cards = new ArrayList<>();
			for (ToolDescriptor match : matches) {
				cards.add(match.name() + " (" + match.category() + "): " + match.description());
			}
			return "Tool result for discover_tools: query=" + query
					+ (catalogOnly ? " matchingTools=" : " activatedTools=") + matches.stream().map(ToolDescriptor::name).toList()
					+ (catalogOnly ? ". These schemas are already advertised; discovery does not change the tool prefix." : "")
				+ " cards=" + cards;
		}
	}

	private record RankedTool(ToolDescriptor tool, int score) {
	}
}
