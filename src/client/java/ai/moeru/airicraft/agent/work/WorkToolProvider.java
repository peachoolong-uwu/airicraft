package ai.moeru.airicraft.agent.work;

import ai.moeru.airicraft.agent.llm.*;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

public final class WorkToolProvider implements PlannerToolProvider {
	private final PlannerActionToolExecutor executor;
	public WorkToolProvider(PlannerActionToolExecutor executor) { this.executor = executor; }
	@Override public String id() { return "work"; }
	@Override public boolean handles(String name) { return List.of("inspect_work", "list_work", "cancel_work", "resume_work").contains(name); }
	@Override public boolean isReadTool(String name) { return name.equals("inspect_work") || name.equals("list_work"); }
	@Override public List<Map<String, Object>> openAiTools() {
		var id = propForProvider("workId", stringForProvider("Exact workId from an action receipt or work inspection."));
		return List.of(
			toolForProvider("inspect_work", "Inspect any job, graph or background process. Omit workId to inspect current foreground work.", propertiesForProvider(id), List.of()),
			toolForProvider("list_work", "List foreground and background work, including retained terminal outcomes and parent relationships.", propertiesForProvider(), List.of()));
	}
	@Override public void validateArguments(String name, JsonObject args) {
		if (!handles(name)) throw new IllegalArgumentException("unknown_work_tool");
		var allowed = name.equals("list_work") ? List.<String>of() : name.equals("cancel_work") ? List.of("workId", "reason")
			: name.equals("resume_work") ? List.of("workId", "holdId") : List.of("workId");
		for (String key : args.keySet()) {
			if (!allowed.contains(key) || !args.get(key).isJsonPrimitive() || !args.get(key).getAsJsonPrimitive().isString()
				|| args.get(key).getAsString().isBlank()) throw new IllegalArgumentException("invalid_work_argument: " + key);
		}
		if (!name.equals("inspect_work") && !name.equals("list_work")) {
			for (String key : allowed) if (!args.has(key)) throw new IllegalArgumentException("missing_work_argument: " + key);
		}
		if (args.has("workId")) new WorkHandle(args.get("workId").getAsString());
	}
	@Override public CompletableFuture<String> execute(PlannerToolCall call) { return executor.execute(call); }
	@Override public String promptInstructions() {
		return "Actions return identified work. Use inspect_work/list_work for all kinds of work, including background furnaces. "
			+ "Accepted means admitted, not completed. Ongoing actions automatically yield until the next meaningful event; their receipts arrive with that event. "
			+ "Use clear_queue to cancel the current plan and continue to keep it or resume after a resolved safety hold. "
			+ "Graph children belong to their parent; control the parent rather than preempting a child.";
	}
}
