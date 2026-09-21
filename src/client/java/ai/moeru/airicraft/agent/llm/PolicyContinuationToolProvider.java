package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

/** Proposal-only schema: this registry has no connection to gameplay execution. */
public final class PolicyContinuationToolProvider implements PlannerToolProvider {
	@Override public String id() { return "policy_continuation"; }
	@Override public boolean handles(String name) { return name.equals("run_policy"); }
	@Override public List<Map<String, Object>> openAiTools() {
		var block = Map.of("type", "object", "properties", Map.of(
			"x", Map.of("type", "integer"), "y", Map.of("type", "integer"), "z", Map.of("type", "integer"),
			"blockId", Map.of("type", "string")), "required", List.of("x", "y", "z", "blockId"), "additionalProperties", false);
		var guard = Map.of("type", "object", "properties", Map.of(
			"parentResult", Map.of("type", "object", "description", "Exact expected parent JSON object return value."),
			"inventoryMin", Map.of("type", "object", "additionalProperties", Map.of("type", "integer", "minimum", 1)),
			"blocks", Map.of("type", "array", "maxItems", 32, "items", block)),
			"required", List.of("parentResult", "inventoryMin", "blocks"), "additionalProperties", false);
		return List.of(toolForProvider("run_policy", "Prepare one guarded next policy. Nothing executes during this request. "
			+ "guard.parentResult must exactly match the parent's expected JSON return value. List all required carried items "
			+ "and observed block targets. If the next step cannot be safely predicted, reply without a tool call.",
			propertiesForProvider(propForProvider("source", stringForProvider("JavaScript function* main(p,input).")),
				propForProvider("input", Map.of("type", "object")), propForProvider("guard", guard)),
			List.of("source", "input", "guard")));
	}
	@Override public void validateArguments(String name, JsonObject args) { if (!handles(name)) throw new IllegalArgumentException("unknown tool"); validate(args); }
	static void validate(JsonObject args) {
		if (!args.keySet().equals(Set.of("source", "input", "guard"))) throw new IllegalArgumentException("continuation_arguments");
		var ordinary = args.deepCopy(); ordinary.remove("guard");
		new PolicyToolProvider(PlannerActionToolExecutor.DISABLED).validateArguments("run_policy", ordinary);
		var guard = args.getAsJsonObject("guard");
		if (!guard.keySet().equals(Set.of("parentResult", "inventoryMin", "blocks")) || !guard.get("parentResult").isJsonObject()
			|| guard.toString().length() > 8192) throw new IllegalArgumentException("continuation_guard");
		for (var count : guard.getAsJsonObject("inventoryMin").entrySet()) {
			if (!count.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || !count.getValue().isJsonPrimitive()
				|| !count.getValue().getAsJsonPrimitive().isNumber() || count.getValue().getAsDouble() != count.getValue().getAsInt()
				|| count.getValue().getAsInt() < 1) throw new IllegalArgumentException("inventory_guard");
		}
		if (guard.getAsJsonArray("blocks").size() > 32) throw new IllegalArgumentException("block_guard_limit");
		for (var entry : guard.getAsJsonArray("blocks")) {
			var block = entry.getAsJsonObject();
			if (!block.keySet().equals(Set.of("x", "y", "z", "blockId"))
				|| !block.get("blockId").getAsString().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("block_guard");
			for (String axis : List.of("x", "y", "z")) if (!block.get(axis).isJsonPrimitive()
				|| !block.get(axis).getAsJsonPrimitive().isNumber() || block.get(axis).getAsDouble() != block.get(axis).getAsInt())
				throw new IllegalArgumentException("block_guard_position");
		}
	}
	@Override public CompletableFuture<String> execute(PlannerToolCall call) { return CompletableFuture.failedFuture(new IllegalStateException("proposal_only")); }
}
