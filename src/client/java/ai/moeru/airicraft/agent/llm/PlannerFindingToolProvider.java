package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

/** Short-term findings live solely in the retained conversation. */
public final class PlannerFindingToolProvider implements PlannerToolProvider {
	public static final String NAME = "record_finding";
	private static final String RETAINED = "Planner finding (raw observation replaced): ";
	private static final String REMINDER = "FINDING REQUIRED: ";
	private static final Set<String> OBSERVATIONS = Set.of("inspect_world", "query_world", "inspect_nearby_entities", "find_world_features");

	@Override public String id() { return "short_term_findings"; }
	@Override public boolean handles(String name) { return NAME.equals(name); }
	@Override public boolean isBatchSafeReadTool(String name) { return false; }
	@Override public List<Map<String, Object>> openAiTools() {
		return List.of(toolForProvider(NAME,
			"Replace the pending raw observation with its task-specific answer in conversation context only. No persistent memory.",
			propertiesForProvider(
				propForProvider("sourceToolCallId", stringForProvider("Exact pending observation tool call ID.")),
				propForProvider("result", Map.of("type", List.of("string", "null"), "description", "Answer locating the requested target; MUST be null when that target was not found, even if the query successfully established negative evidence. Put negative evidence in memory.", "maxLength", 1024)),
				propForProvider("memory", Map.of("type", "string", "description", "Concise evidence needed for the task: exact coordinates, materials, coverage, uncertainties. For null, retain what was checked, ruled out or failed, and what remains unchecked.", "maxLength", 4096))
			), List.of("sourceToolCallId", "result", "memory")));
	}
	@Override public String promptInstructions() {
		return "Experimental short-term findings: call tools sequentially. After inspect_world, query_world, inspect_nearby_entities, find_world_features or custom_ queries, you MUST call record_finding alone before any other tool or final reply. "
			+ "Answer the original task-specific question, not a general description of surroundings. Preserve exact actionable coordinates/materials and uncertainty. Use result:null whenever the requested target was not found, even if the query ran successfully. For example, searching for a wall hole and finding an intact wall requires result:null, with the intact checked section and remaining search area in memory. "
			+ "Only your finding and the original query remain in context; raw results are removed after acceptance. Findings are observations at query time, not eternal facts.";
	}
	@Override public void validateArguments(String name, JsonObject args) {
		requireText(args, "sourceToolCallId", 256);
		requireText(args, "memory", 4096);
		if (!args.has("result")) throw new IllegalArgumentException("record_finding requires result (string or null)");
		if (!args.get("result").isJsonNull()) requireText(args, "result", 1024);
	}
	private static void requireText(JsonObject args, String key, int max) {
		var value = args.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
			|| value.getAsString().isBlank() || value.getAsString().length() > max)
			throw new IllegalArgumentException("record_finding requires nonempty " + key + " string (max " + max + " characters)");
	}
	@Override public CompletableFuture<String> execute(PlannerToolCall call) {
		validateArguments(call.name(), call.arguments());
		return CompletableFuture.completedFuture("Finding accepted into conversation context.");
	}

	static LlmChatMessage pending(LlmConversation conversation) {
		var observations = new HashMap<String, PlannerToolCall>();
		for (var message : conversation.messages()) {
			for (var call : message.toolCalls()) {
				if (OBSERVATIONS.contains(call.name()) || call.name().startsWith("custom_")) observations.put(call.id(), call);
			}
			if ("tool".equals(message.role()) && observations.containsKey(message.toolCallId()) && !message.content().startsWith(RETAINED)) return message;
		}
		return null;
	}

	static String validateNext(LlmConversation conversation, List<PlannerToolCall> calls) {
		var pending = pending(conversation);
		if (calls.size() > 1) return "Short-term findings require exactly one tool per response";
		boolean finding = calls.size() == 1 && NAME.equals(calls.getFirst().name());
		if (pending == null) return finding ? "No observation is awaiting record_finding" : null;
		if (!finding) return "Call record_finding alone for sourceToolCallId=" + pending.toolCallId() + " before another tool or reply";
		try { new PlannerFindingToolProvider().validateArguments(NAME, calls.getFirst().arguments()); }
		catch (IllegalArgumentException error) { return error.getMessage(); }
		return pending.toolCallId().equals(calls.getFirst().arguments().get("sourceToolCallId").getAsString()) ? null : "record_finding sourceToolCallId must match " + pending.toolCallId();
	}

	static LlmConversation afterTool(LlmConversation conversation, PlannerToolCall call, String receipt) {
		if (NAME.equals(call.name()) && !receipt.startsWith("TOOL_ERROR:")) {
			String sourceId = call.arguments().get("sourceToolCallId").getAsString();
			var messages = new ArrayList<LlmChatMessage>();
			for (var message : conversation.messages()) {
				if (message.content().startsWith(REMINDER) || call.id().equals(message.toolCallId())
					|| message.toolCalls().stream().anyMatch(t -> call.id().equals(t.id()))) continue;
				messages.add(sourceId.equals(message.toolCallId()) ? LlmChatMessage.tool(sourceId, RETAINED + call.arguments()) : message);
			}
			return LlmConversation.of(messages);
		}
		var pending = pending(conversation);
		return pending == null ? conversation : conversation.withAppended(LlmChatMessage.user(REMINDER
			+ "Call record_finding alone with sourceToolCallId=" + pending.toolCallId()
			+ ". Extract the answer needed for your task, or result:null with checked coverage/failure and remaining uncertainty. Raw output will then leave context.", LlmMessageKind.NOTICE));
	}
}
