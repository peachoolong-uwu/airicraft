package ai.moeru.airicraft.agent.llm.goal;

import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import ai.moeru.airicraft.agent.llm.PlannerToolProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

public final class PlannerGoalToolProvider implements PlannerToolProvider {
	private final PlannerGoalStore store;
	private final Executor clientExecutor;

	public PlannerGoalToolProvider(PlannerGoalStore store, Executor clientExecutor) {
		this.store = store;
		this.clientExecutor = clientExecutor;
	}

	@Override public String id() { return "planner_goal"; }
	@Override public boolean handles(String name) {
		return List.of("set_planner_goal", "change_planner_goal", "finish_planner_goal", "inspect_planner_goal").contains(name);
	}
	@Override public boolean isReadTool(String name) { return "inspect_planner_goal".equals(name); }

	@Override public List<Map<String, Object>> openAiTools() {
		var narration = propForProvider("narration", optionalStringForProvider("Optional short visible narration."));
		var objective = propForProvider("objective", stringForProvider("Concrete longer-term objective, durable constraints and observable completion conditions, up to 2048 characters. Do not store current inventory, health, or temporary progress as facts in the objective."));
		var id = propForProvider("goalId", stringForProvider("Exact current planner goal id."));
		return List.of(
			toolForProvider("set_planner_goal", "Start a persistent planner goal. Requires no active planner goal. Jobs are individual steps toward this objective.", propertiesForProvider(narration, objective), List.of("objective")),
			toolForProvider("change_planner_goal", "Replace the active objective, preserving the reason. Returns a new goal id. Does not cancel a running action; cancel that separately when necessary.", propertiesForProvider(narration, id, objective, propForProvider("reason", stringForProvider("Why the objective changed."))), List.of("goalId", "objective", "reason")),
			toolForProvider("finish_planner_goal", "End the planner goal explicitly with verified success or a reason for giving up. Stops automatic goal continuation; does not cancel a running action.", propertiesForProvider(narration, id,
				propForProvider("status", Map.of("type", "string", "enum", List.of("success", "give_up"))),
				propForProvider("outcome", stringForProvider("Concrete completion evidence, or why progress cannot continue. Up to 2048 characters."))), List.of("goalId", "status", "outcome")),
			toolForProvider("inspect_planner_goal", "Read the current world-persisted planner objective, identity, status, and outcome.", propertiesForProvider(narration), List.of())
		);
	}

	@Override public String promptInstructions() {
		return """
			For multi-stage work or autonomous self-play, set_planner_goal before acting. The planner goal carries purpose across replies, task completion, compaction, and world reloads. Do not create a goal for ordinary conversation.
			An ACTIVE planner goal continues automatically when the planner and action executor are idle. A plaintext reply is a yield, not completion. While a job runs, yield and wait for its terminal update instead of polling inspect_action_goal repeatedly. When free, choose the next useful step without asking the human to say continue.
			Use change_planner_goal when the objective changes; preserve user constraints. Use finish_planner_goal success only with observed completion evidence, or give_up with a concrete reason when stuck or human input is essential. On a user stop, cancel active action work and finish the planner goal with give_up. clear_goal only clears the action/navigation goal, not this planner objective.
			Do not restart a finished goal unless newly requested or in an explicit initiative window. After reload or respawn, inspect fresh world and inventory state before resuming. Store desired outcomes and durable constraints in objectives; do not embed current supplies, health or temporary progress. If an old goal contains such facts, newer observations take precedence; use change_planner_goal to remove stale checkpoint facts while preserving its purpose. Completed jobs do not by themselves complete the planner goal.
			""";
	}

	@Override public String contextSnapshot() { return "Current planner goal (stored intent; any inventory, health or progress claims are historical, not current observations): " + store.context(); }

	@Override public void validateArguments(String name, JsonObject args) {
		List<String> fields = switch (name) {
			case "set_planner_goal" -> List.of("objective");
			case "change_planner_goal" -> List.of("goalId", "objective", "reason");
			case "finish_planner_goal" -> List.of("goalId", "status", "outcome");
			case "inspect_planner_goal" -> List.of();
			default -> throw new JsonParseException("unknown planner goal tool");
		};
		for (String key : args.keySet()) if (!fields.contains(key) && !key.equals("narration")) throw new JsonParseException("unknown argument: " + key);
		for (String key : fields) text(args, key);
		if (name.equals("finish_planner_goal") && !List.of("success", "give_up").contains(text(args, "status"))) throw new JsonParseException("status must be success or give_up");
	}

	private static String text(JsonObject args, String name) {
		if (!args.has(name) || !args.get(name).isJsonPrimitive() || !args.getAsJsonPrimitive(name).isString()) throw new JsonParseException("missing string: " + name);
		try { return PlannerGoalStore.checked(args.get(name).getAsString(), name); }
		catch (IllegalArgumentException exception) { throw new JsonParseException(exception.getMessage()); }
	}

	@Override public CompletableFuture<String> execute(PlannerToolCall call) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				var args = call.arguments();
				validateArguments(call.name(), args);
				store.refreshWorld();
				switch (call.name()) {
					case "set_planner_goal" -> store.set(text(args, "objective"));
					case "change_planner_goal" -> store.change(text(args, "goalId"), text(args, "objective"), text(args, "reason"));
					case "finish_planner_goal" -> store.finish(text(args, "goalId"), text(args, "status").equals("success") ? PlannerGoalStore.Status.SUCCEEDED : PlannerGoalStore.Status.GIVEN_UP, text(args, "outcome"));
					case "inspect_planner_goal" -> { }
					default -> throw new IllegalArgumentException("unknown planner goal tool");
				}
				return "Tool result for " + call.name() + ": " + store.context();
			}
			catch (IOException | RuntimeException exception) { return "TOOL_ERROR: " + call.name() + ": " + exception.getMessage(); }
		}, clientExecutor);
	}
}
