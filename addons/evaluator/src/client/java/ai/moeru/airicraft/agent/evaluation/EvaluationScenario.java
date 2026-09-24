package ai.moeru.airicraft.agent.evaluation;

import java.nio.file.Path;
import java.util.List;

public record EvaluationScenario(
	String id,
	String name,
	String minecraftVersion,
	String airicraftVersion,
	Path configPath,
	String worldArchive,
	boolean frozen,
	String prompt,
	EvaluationBudget budget,
	List<EvaluationCheck> checks,
	List<EvaluationWaypoint> waypoints,
	EvaluationEvidenceSettings evidence,
	List<String> requiredMods
) {
	public static final List<String> SUPPORTED_MODS = List.of("journeymap", "roughlyenoughitems");

	public EvaluationScenario(String id, String name, String minecraftVersion, String airicraftVersion,
		Path configPath, String worldArchive, boolean frozen, String prompt, EvaluationBudget budget,
		List<EvaluationCheck> checks, List<EvaluationWaypoint> waypoints, EvaluationEvidenceSettings evidence) {
		this(id, name, minecraftVersion, airicraftVersion, configPath, worldArchive, frozen, prompt,
			budget, checks, waypoints, evidence, List.of());
	}

	public EvaluationScenario {
		id = id == null ? "" : id.trim();
		name = name == null || name.isBlank() ? id : name.trim();
		minecraftVersion = minecraftVersion == null ? "" : minecraftVersion.trim();
		airicraftVersion = airicraftVersion == null ? "" : airicraftVersion.trim();
		worldArchive = worldArchive == null || worldArchive.isBlank() ? "world.zip" : worldArchive.trim();
		prompt = prompt == null ? "" : prompt;
		budget = budget == null ? EvaluationBudget.defaults() : budget;
		checks = checks == null ? List.of() : List.copyOf(checks);
		waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
		requiredMods = requiredMods == null ? List.of() : List.copyOf(requiredMods);
		if (!SUPPORTED_MODS.containsAll(requiredMods)) {
			throw new IllegalArgumentException("requiredMods must contain supported mod IDs: " + SUPPORTED_MODS);
		}
		requiredMods = requiredMods.stream().distinct().toList();
		evidence = evidence == null ? EvaluationEvidenceSettings.defaults() : evidence;
	}

	public boolean hasDeterministicChecks() {
		return checks.stream().anyMatch(check -> !"external_judge".equals(check.type()) && !"subjective".equals(check.type()));
	}
}
