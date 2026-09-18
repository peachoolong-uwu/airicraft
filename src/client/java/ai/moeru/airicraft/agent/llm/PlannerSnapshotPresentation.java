package ai.moeru.airicraft.agent.llm;

import com.google.gson.*;
import java.util.ArrayList;
import java.util.List;

/** Request-local projection: replaying the same accepted history produces the same deltas.
 * Full observations remain available to the flight recorder. No unsent observation advances a baseline. */
final class PlannerSnapshotPresentation {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final String PREFIX = "DECISION CONTEXT: ";
	private JsonObject previous;
	private JsonElement world;

	String message(String role, String content) {
		if (!role.equals("user")) return content;
		String[] paragraphs = content.split("\n\n", -1);
		for (int i = 0; i < paragraphs.length; i++) {
			if (!paragraphs[i].startsWith(PREFIX + "{")) continue;
			JsonObject payload;
			try { payload = JsonParser.parseString(paragraphs[i].substring(PREFIX.length())).getAsJsonObject(); }
			catch (JsonParseException | IllegalStateException exception) { continue; }
			if (!payload.has("current") || !payload.get("current").isJsonObject()) continue;
			JsonObject current = payload.getAsJsonObject("current");
			// The authoritative objective is already supplied below, as a baseline or a delta.
			// Retain event identity/time without repeating the entire decision notebook.
			if (payload.has("events") && payload.get("events").isJsonArray()) {
				for (JsonElement item : payload.getAsJsonArray("events")) {
					if (!item.isJsonObject()) continue;
					JsonObject event = item.getAsJsonObject();
					if (!new JsonPrimitive("objective.changed").equals(event.get("type"))
						|| !event.has("payload") || !event.get("payload").isJsonObject()) continue;
					JsonObject evidence = event.getAsJsonObject("payload");
					if (evidence.has("objective") && evidence.get("objective").equals(current.get("objective"))) {
						evidence.remove("objective");
						evidence.addProperty("objectiveState", "same as current state baseline or changes");
					}
				}
			}
			boolean baseline = previous == null || !java.util.Objects.equals(world, payload.get("worldSessionId"))
				|| payload.has("missingEventRange") || payload.has("stateBaseline");
			if (baseline) payload.addProperty("stateBaseline", true);
			else {
				JsonArray changes = new JsonArray();
				diff(previous, current, List.of(), changes);
				payload.remove("current");
				if (!changes.isEmpty()) payload.add("stateChanges", changes);
			}
			previous = current;
			world = payload.get("worldSessionId");
			paragraphs[i] = PREFIX + payload;
		}
		return String.join("\n\n", paragraphs);
	}

	private static void diff(JsonObject before, JsonObject after, List<String> path, JsonArray changes) {
		for (String key : before.keySet()) if (!after.has(key)) {
			JsonObject change = new JsonObject();
			change.add("remove", GSON.toJsonTree(child(path, key)));
			changes.add(change);
		}
		for (var entry : after.entrySet()) {
			JsonElement old = before.get(entry.getKey()), value = entry.getValue();
			if (value.equals(old)) continue;
			List<String> next = child(path, entry.getKey());
			if (old != null && old.isJsonObject() && value.isJsonObject()) diff(old.getAsJsonObject(), value.getAsJsonObject(), next, changes);
			else {
				JsonObject change = new JsonObject();
				change.add("set", GSON.toJsonTree(next));
				change.add("value", value);
				changes.add(change);
			}
		}
	}

	private static List<String> child(List<String> path, String key) {
		var next = new ArrayList<>(path);
		next.add(key);
		return next;
	}
}
