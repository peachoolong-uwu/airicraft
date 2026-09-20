package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.policy.GraalPolicyInvocation;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

/** Disabled action-policy adapter retained for isolated engine tests. */
public final class PolicyToolProvider implements PlannerToolProvider {
	public PolicyToolProvider(PlannerActionToolExecutor executor) { }
	@Override public String id() { return "policy"; }
	@Override public boolean available() { return false; }
	@Override public boolean handles(String name) { return name.equals("run_policy"); }
	@Override public boolean isReadTool(String name) { return false; }
	@Override public boolean endsTurn(String name) { return name.equals("run_policy"); }
	@Override public List<Map<String, Object>> openAiTools() {
		return List.of(toolForProvider("run_policy", "Run a bounded JavaScript generator that sequences native gameplay tools without planner turns between actions. "
			+ "Define function* main(p, input). Yield named functions such as p.navigateTo(args), p.mineBlocks(args), p.craftRecipe(args), "
			+ "p.placeBlock(args), p.queryWorld(args), p.inspectInventory(args). Functions use the corresponding tool arguments. "
			+ "Yield p.describe('craftRecipe') for its exact schema, or read_policy_docs for the complete API. "
			+ "Each tool call awaits its identified work and returns {ok,tool,result,work?}; check ok before continuing. "
			+ "Existing observeContainer/withdraw/closeContainer(syncId) helpers retain verified singleplayer container semantics. "
			+ "No open chest required for other methods. Maximum 128 effects and 12000 client ticks. "
			+ "Returns root work and yields this planner turn; cancel_work or safety interruption stops the policy and its active child. "
			+ "Committed effects remain. No host access, recursive policies, planner/delegation controls or image/LLM tools.",
			propertiesForProvider(propForProvider("source", stringForProvider("JavaScript defining function* main(policy, input), maximum 32768 characters.")),
				propForProvider("input", Map.of("type", "object", "description", "JSON input for this invocation."))), List.of("source", "input")));
	}
	@Override public void validateArguments(String name, JsonObject args) {
		if (!handles(name) || !args.keySet().equals(java.util.Set.of("source", "input"))) throw new IllegalArgumentException("invalid_policy_arguments");
		if (!args.get("source").isJsonPrimitive() || !args.get("source").getAsJsonPrimitive().isString()
			|| args.get("source").getAsString().isBlank() || args.get("source").getAsString().length() > GraalPolicyInvocation.MAX_SOURCE_CHARS)
			throw new IllegalArgumentException("policy_source_limit");
		if (!args.get("input").isJsonObject() || args.get("input").toString().length() > GraalPolicyInvocation.MAX_VALUE_CHARS)
			throw new IllegalArgumentException("policy_input_limit");
	}
	@Override public CompletableFuture<String> execute(PlannerToolCall call) { return CompletableFuture.completedFuture("TOOL_UNAVAILABLE: run_policy disabled"); }
}
