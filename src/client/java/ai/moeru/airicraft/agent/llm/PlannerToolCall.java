package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;

public record PlannerToolCall(
	String id,
	String name,
	JsonObject arguments,
	String narration,
	JsonElement rawToolCall,
	List<String> repairedArgumentPaths
) {
	public PlannerToolCall(String id, String name, JsonObject arguments, String narration, JsonElement rawToolCall) {
		this(id, name, arguments, narration, rawToolCall, List.of());
	}

	public PlannerToolCall {
		id = id == null || id.isBlank() ? "call_planner_tool" : id;
		name = name == null ? "" : name;
		arguments = arguments == null ? new JsonObject() : arguments.deepCopy();
		narration = narration == null || narration.isBlank() ? null : narration.trim();
		rawToolCall = rawToolCall == null || rawToolCall.isJsonNull() ? null : rawToolCall.deepCopy();
		repairedArgumentPaths = List.copyOf(repairedArgumentPaths);
	}
}
