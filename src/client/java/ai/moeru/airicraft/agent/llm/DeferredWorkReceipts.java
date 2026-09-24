package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.work.WorkSnapshot;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Receipts retained locally until another event warrants a model request. */
final class DeferredWorkReceipts {
	private record Receipt(String toolName, String workId) {}
	private final Map<String, Receipt> pending = new LinkedHashMap<>();

	boolean defer(PlannerToolCall call, String text) {
		String prefix = "Tool result for " + call.name() + ": ";
		if (!text.startsWith(prefix)) return false;
		try {
			var receipt = JsonParser.parseString(text.substring(prefix.length())).getAsJsonObject();
			if (!receipt.has("accepted") || !receipt.get("accepted").getAsBoolean()
				|| !receipt.has("workId") || !receipt.has("state")
				|| !List.of("QUEUED", "RUNNING", "WAITING").contains(receipt.get("state").getAsString())) return false;
			pending.put(call.id(), new Receipt(call.name(), receipt.get("workId").getAsString()));
			return true;
		} catch (IllegalStateException | IllegalArgumentException | com.google.gson.JsonParseException exception) {
			return false;
		}
	}

	LlmConversation deliver(LlmConversation conversation, Map<String, Object> current) {
		if (pending.isEmpty()) return conversation;
		List<?> work = current.get("work") instanceof List<?> entries ? entries : List.of();
		var messages = conversation.messages().stream().map(message -> {
			Receipt receipt = pending.get(message.toolCallId());
			if (receipt == null || !message.role().equals("tool")) return message;
			for (Object entry : work) {
				if (entry instanceof Map<?, ?> outcome && receipt.workId().equals(outcome.get("workId"))
					&& List.of("FAILED", "CANCELLED", "SUCCEEDED").contains(String.valueOf(outcome.get("state")))) {
					return LlmChatMessage.tool(message.toolCallId(), "Tool result for " + receipt.toolName() + ": "
						+ new Gson().toJson(WorkSnapshot.summarize(outcome)));
				}
			}
			return message;
		}).toList();
		pending.clear();
		return LlmConversation.of(messages);
	}

	void clear() { pending.clear(); }
}
