package ai.moeru.airicraft.agent.llm.delegation;

import ai.moeru.airicraft.agent.llm.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

public final class PlannerDelegationToolProvider implements PlannerToolProvider {
	public enum Role { CONTROLLER, THINKING }
	private final Role role;
	private final PlannerDelegation delegation;
	private final Executor clientExecutor;
	private final Supplier<String> controllerContext;
	private final BooleanSupplier workIdle;

	public PlannerDelegationToolProvider(Role role, PlannerDelegation delegation, Executor clientExecutor,
		Supplier<String> controllerContext, BooleanSupplier workIdle) {
		this.role = role;
		this.delegation = delegation;
		this.clientExecutor = clientExecutor;
		this.controllerContext = controllerContext;
		this.workIdle = workIdle;
	}
	@Override public String id() { return "system2_" + role.name().toLowerCase(java.util.Locale.ROOT); }
	private String toolName() { return role == Role.CONTROLLER ? "delegate_task" : "return_control"; }
	@Override public boolean handles(String name) { return toolName().equals(name) || role == Role.THINKING && name.equals("record_decision"); }
	@Override public boolean isReadTool(String name) { return false; }
	@Override public boolean endsTurn(String name) { return name.equals("return_control"); }
	@Override public String promptInstructions() {
		return role == Role.CONTROLLER
			? "You are the non-thinking main controller. Handle most gameplay directly: gathering ordinary resources, crafting known recipes, using furnaces, inspecting inventory, and navigating to known locations do not need thinking, even when they take several tool calls. Reserve delegate_task for substantial spatial reasoning or planning across interacting constraints: designing a shelter, choosing a suitable farm site and layout, or organising inventory and storage. Routine inventory checks and known item transfers stay with you; deciding what to keep, store, retrieve, and allocate across a trip or build may benefit from thinking. A long task or one failed tool call alone is not a reason to delegate; inspect the result and try a straightforward correction first. When delegation is warranted or explicitly requested, provide a bounded task and observable success criteria. State the purpose and real constraints; leave design choices to the thinker. For construction, distinguish interior dimensions from exterior bounds and prefer a usable result over an unnecessarily exact blueprint. The thinking planner takes over gameplay until it returns. Review its observed actions and final facts before choosing the next step."
			: "You are the thinking planner receiving delegated gameplay tasks. You own gameplay tools while delegated. Continue the task across plaintext yields and task updates. Call return_control with the exact delegationId and success/give_up when done; all gameplay jobs must finish or be cancelled first. Preserve the controller's overall objective and user constraints. Do not replace or finish the overall planner goal just because your delegated task ends. No recursive delegation. For reversible construction, choose a workable layout and start with a small inspected, reachable section. Inspect the result and adapt while building; break and replace your own newly placed blocks when useful. Preserve existing equipment, user constraints and a usable exit. Reconsider the overall design only when new evidence invalidates it. Do not repeatedly enumerate every future block or optimize the whole structure before taking the next useful action.";
	}
	@Override public List<Map<String, Object>> openAiTools() {
		return role == Role.CONTROLLER ? List.of(toolForProvider("delegate_task", "Hand a bounded complex task to the thinking planner. It takes over tools and returns actual action evidence and final state.", propertiesForProvider(
			propForProvider("task", stringForProvider("Task and important constraints, up to 2048 characters.")),
			propForProvider("successCriteria", stringForProvider("Observable completion conditions, up to 2048 characters."))), List.of("task", "successCriteria")))
			: List.of(toolForProvider("return_control", "End the delegated task and hand gameplay back to the controller. Requires all gameplay work idle.", propertiesForProvider(
			propForProvider("delegationId", stringForProvider("Exact current delegation identity.")),
			propForProvider("status", Map.of("type", "string", "enum", List.of("success", "give_up"))),
			propForProvider("outcome", stringForProvider("Evidence for completion or concrete reason for giving up, up to 2048 characters."))), List.of("delegationId", "status", "outcome")), toolForProvider("record_decision", "Store or replace a bounded named decision for this assignment; this cannot change the overall objective.", propertiesForProvider(
			propForProvider("delegationId", stringForProvider("Current assignment identity.")), propForProvider("name", stringForProvider("Stable name up to 64 characters.")),
			propForProvider("decision", stringForProvider("Planning choice, not observed world facts.")), propForProvider("reason", stringForProvider("Why this choice is appropriate."))), List.of("delegationId", "name", "decision", "reason")));
	}
	@Override public void validateArguments(String name, JsonObject args) {
		List<String> fields = name.equals("record_decision") ? List.of("delegationId", "name", "decision", "reason") : role == Role.CONTROLLER ? List.of("task", "successCriteria") : List.of("delegationId", "status", "outcome");
		if (!handles(name)) throw new JsonParseException("Unknown delegation tool");
		for (String field : fields) {
			if (!args.has(field) || !args.get(field).isJsonPrimitive() || !args.getAsJsonPrimitive(field).isString()
				|| args.get(field).getAsString().isBlank() || args.get(field).getAsString().length() > 2048)
				throw new JsonParseException("Expected nonblank string up to 2048 characters: " + field);
		}
		for (String field : args.keySet()) if (!fields.contains(field)) throw new JsonParseException("Unknown field: " + field);
	}
	@Override public CompletableFuture<String> execute(PlannerToolCall call) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				validateArguments(call.name(), call.arguments());
				var args = call.arguments();
				if (role == Role.CONTROLLER)
					return delegation.delegate(args.get("task").getAsString(), args.get("successCriteria").getAsString(), controllerContext.get());
				if (call.name().equals("record_decision")) return CompletableFuture.completedFuture("Tool result for record_decision: " + delegation.decide(args.get("delegationId").getAsString(), args.get("name").getAsString(), args.get("decision").getAsString(), args.get("reason").getAsString()));
				delegation.requestReturn(args.get("delegationId").getAsString(), args.get("status").getAsString(), args.get("outcome").getAsString(), workIdle.getAsBoolean());
				return CompletableFuture.completedFuture("Tool result for return_control: accepted; gameplay decisions return to the controller.");
			} catch (RuntimeException exception) {
				return CompletableFuture.completedFuture("TOOL_ERROR: " + call.name() + ": " + exception.getMessage());
			}
		}, clientExecutor).thenCompose(result -> result);
	}
}
