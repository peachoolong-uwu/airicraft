package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.llm.ExternalPlannerToolResult;
import ai.moeru.airicraft.agent.work.WorkHandle;
import ai.moeru.airicraft.agent.work.WorkSnapshot;
import ai.moeru.airicraft.policy.PolicyRuntime;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;

/** Sequential tool adapter. Admission is not completion; tracked work is awaited by identity. */
final class ToolPolicyHost implements PolicyRuntime.Host {

	private final BiFunction<String, JsonObject, CompletableFuture<ExternalPlannerToolResult>> dispatch;
	private final Function<String, JsonElement> describe;
	private final Function<WorkHandle, Optional<WorkSnapshot>> work;
	private final Runnable cancelChildren;
	private final Supplier<PolicyRuntime.Host> containers;
	private PolicyRuntime.Host container;
	private CompletableFuture<ExternalPlannerToolResult> pending;
	private CompletableFuture<JsonElement> result;
	private JsonObject response;
	private WorkHandle awaited;
	private String tool;
	private boolean closed;

	ToolPolicyHost(BiFunction<String, JsonObject, CompletableFuture<ExternalPlannerToolResult>> dispatch,
		Function<String, JsonElement> describe, Function<WorkHandle, Optional<WorkSnapshot>> work,
		Runnable cancelChildren, Supplier<PolicyRuntime.Host> containers) {
		this.dispatch = dispatch; this.describe = describe; this.work = work;
		this.cancelChildren = cancelChildren; this.containers = containers;
	}

	@Override public CompletableFuture<JsonElement> execute(JsonObject effect) {
		if (closed) throw new IllegalStateException("policy_cancelled");
		if (!effect.keySet().equals(Set.of("operation", "arguments"))) throw new IllegalArgumentException("invalid_policy_effect");
		String operation = effect.get("operation").getAsString();
		if (!operation.equals("call_tool") && !operation.equals("describe_tool")) {
			if (container == null) container = containers.get();
			return container.execute(effect);
		}
		JsonObject args = effect.getAsJsonObject("arguments");
		tool = args.get("name").getAsString();
		tool = ai.moeru.airicraft.policy.PolicyTools.methods().getOrDefault(tool, tool);
		if (!ai.moeru.airicraft.policy.PolicyTools.TOOLS.contains(tool)) return CompletableFuture.completedFuture(failure(tool, "unsupported_policy_tool"));
		if (operation.equals("describe_tool")) return CompletableFuture.completedFuture(describe.apply(tool));
		if (!args.keySet().equals(Set.of("name", "args")) || !args.get("args").isJsonObject())
			throw new IllegalArgumentException("invalid_policy_tool_arguments");
		// Ordinary tool calls may open/close/change windows; bind legacy helpers afresh afterward.
		if (container != null) { container.close(); container = null; }
		result = new CompletableFuture<>();
		response = null; awaited = null;
		try { pending = dispatch.apply(tool, args.getAsJsonObject("args")); }
		catch (RuntimeException error) { result.complete(failure(tool, message(error))); }
		return result;
	}

	@Override public void tick() {
		if (closed) return;
		if (container != null) container.tick();
		if (result == null || result.isDone()) return;
		try {
			if (response == null) {
				if (!pending.isDone()) return;
				var output = pending.join();
				String text = output.text();
				if (text.startsWith("TOOL_ERROR:") || text.startsWith("TOOL_UNAVAILABLE:")) {
					result.complete(failure(tool, text)); return;
				}
				String prefix = "Tool result for " + tool + ": ";
				String payload = text.startsWith(prefix) ? text.substring(prefix.length()) : text;
				JsonElement value;
				try { value = JsonParser.parseString(payload); }
				catch (JsonParseException error) { value = new JsonPrimitive(payload); }
				response = new JsonObject(); response.addProperty("tool", tool); response.addProperty("ok", true);
				response.add("result", value);
				if (value.isJsonObject()) {
					var receipt = value.getAsJsonObject();
					if (receipt.has("accepted") && !receipt.get("accepted").getAsBoolean()) response.addProperty("ok", false);
					if (receipt.has("workId")) awaited = new WorkHandle(receipt.get("workId").getAsString());
				}
				if (awaited == null) { result.complete(response); return; }
			}
			var current = work.apply(awaited).orElseThrow(() -> new IllegalStateException("policy_child_work_lost: " + awaited.id()));
			if (!current.state().terminal()) return;
			response.addProperty("ok", current.state() == WorkSnapshot.State.SUCCEEDED);
			response.add("work", new Gson().toJsonTree(current.summary()));
			result.complete(response);
		} catch (RuntimeException error) { result.complete(failure(tool, message(error))); }
	}

	private static JsonObject failure(String tool, String error) {
		var value = new JsonObject(); value.addProperty("ok", false); value.addProperty("tool", tool); value.addProperty("error", error); return value;
	}
	private static String message(Throwable error) {
		while (error.getCause() != null) error = error.getCause();
		return Objects.toString(error.getMessage(), error.getClass().getSimpleName());
	}
	@Override public void close() {
		if (closed) return;
		closed = true;
		if (container != null) container.close();
		cancelChildren.run();
		if (result != null && !result.isDone()) result.completeExceptionally(new IllegalStateException("policy_cancelled"));
	}
}
