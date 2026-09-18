package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.policy.GraalPolicyInvocation;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

/** One finite invocation, exposed only to the controller planner. */
public final class PolicyToolProvider implements PlannerToolProvider {
	private static final String GUIDANCE = PolicyDocsToolProvider.readResource("/prompts/planner-policy.md");
	private final PlannerActionToolExecutor executor;
	public PolicyToolProvider(PlannerActionToolExecutor executor) { this.executor = executor; }
	@Override public String id() { return "policy"; }
	@Override public String promptInstructions() { return GUIDANCE; }
	@Override public boolean handles(String name) { return name.equals("run_policy"); }
	@Override public boolean isReadTool(String name) { return false; }
	@Override public boolean endsTurn(String name) { return name.equals("run_policy"); }
	@Override public List<Map<String, Object>> openAiTools() {
		return List.of(toolForProvider("run_policy", "Run a finite JavaScript generator against an already-open chest in singleplayer. "
			+ "Define function* main(policy, input). Yield policy.observeContainer() for fresh server-observed {syncId,container,inventory} item-count maps; "
			+ "yield policy.withdraw(syncId,[{itemId,quantity}]) to await a verified transfer and receive the updated snapshot; "
			+ "yield policy.closeContainer(syncId) to await closure. Use local branches/loops and return a JSON result. "
			+ "No host access, navigation, persistence or other tools. Maximum 32 effects and 1200 client ticks. "
			+ "Returns identified work and yields this planner turn until completion; cancel_work cancels it. "
			+ "Safety interruptions cancel the policy; committed transfers remain. Inspect fresh counts before a retry.",
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
	@Override public CompletableFuture<String> execute(PlannerToolCall call) { return executor.execute(call); }
}
