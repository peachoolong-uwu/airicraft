package ai.moeru.airicraft.os;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Guest declarations contain requests, never native IDs, grants or scheduler priorities. */
final class SkillEffects {
	private static final Map<String, Set<String>> FIELDS = Map.of(
		"observe", Set.of("query"), "wait", Set.of("condition", "options"), "spawn", Set.of("definition", "input", "options"),
		"join", Set.of("handle"), "target", Set.of("resource", "quantity"), "demand", Set.of("resource", "quantity", "methods"),
		"work", Set.of("operation", "arguments", "context"), "worker", Set.of("definition", "input"));
	private SkillEffects() {}
	static JsonObject effect(JsonElement value) {
		var effect = OsJson.object(OsJson.copy(value)); var kind = OsJson.text(effect, "kind");
		if (!FIELDS.containsKey(kind)) throw new IllegalArgumentException("effect_unknown");
		var fields = new HashSet<>(FIELDS.get(kind)); fields.add("kind"); OsJson.keys(effect, fields, fields);
		for (var key : Set.of("query", "condition", "options", "handle", "arguments")) if (effect.has(key)) OsJson.object(effect.get(key));
		for (var key : Set.of("definition", "resource", "operation")) if (effect.has(key)) OsJson.text(effect, key);
		if (effect.has("quantity") && (OsJson.number(effect, "quantity", 0) > Integer.MAX_VALUE || kind.equals("demand") && OsJson.number(effect, "quantity", 0) == 0)) throw new IllegalArgumentException("invalid_quantity");
		if (kind.equals("demand")) SkillDefinition.strings(effect.get("methods"), 32);
		if (kind.equals("work") && !effect.get("context").isJsonNull()) OsJson.text(effect, "context");
		return effect;
	}
	static void result(JsonElement value, String mode) {
		value = OsJson.copy(value);
		if (mode.equals("offers")) {
			if (!value.isJsonArray() || value.getAsJsonArray().size() > 32) throw new IllegalArgumentException("work_offer_limit");
			for (var item : value.getAsJsonArray()) if (!OsJson.text(effect(item), "kind").equals("work")) throw new IllegalArgumentException("invalid_work_offer");
		} else {
			var result = OsJson.object(value); OsJson.keys(result, Set.of("done", "value"), Set.of("done", "value"));
			if (!OsJson.bool(result, "done", false)) effect(result.get("value"));
		}
	}
}
