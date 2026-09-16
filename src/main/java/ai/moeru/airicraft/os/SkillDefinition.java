package ai.moeru.airicraft.os;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Immutable, content-addressed behavior or pure worker declaration. */
public final class SkillDefinition {
	public static final int MAX_BYTES = 524_288;
	private final JsonObject value;
	private final JsonContract input, output;
	private final String digest;
	public SkillDefinition(JsonElement declaration) {
		value = OsJson.object(OsJson.copy(declaration, MAX_BYTES, 20, 8192));
		var fields = new HashSet<>(Set.of("schemaVersion", "kind", "name", "description", "tags", "capabilities", "environment", "dependencies", "inputContract", "outputContract", "examples"));
		if (kind().equals("behavior")) fields.addAll(Set.of("mode", "source"));
		else if (kind().equals("worker")) fields.addAll(Set.of("prompt", "profile", "fallback"));
		else throw new IllegalArgumentException("invalid_definition_kind");
		var allowed = new HashSet<>(fields); if (kind().equals("worker")) allowed.add("reconsideration");
		OsJson.keys(value, allowed, fields);
		if (OsJson.number(value, "schemaVersion", 0) != 1 || name().length() > 128) throw new IllegalArgumentException("invalid_definition");
		longText("description", 2048); strings(value.get("tags"), 32); capabilities(); environment(); dependencies();
		input = new JsonContract(value.get("inputContract")); output = new JsonContract(value.get("outputContract"));
		if (kind().equals("behavior")) {
			if (!Set.of("generator", "offers").contains(mode())) throw new IllegalArgumentException("invalid_skill_mode");
			longText("source", 65_536);
		} else {
			longText("prompt", 65_536); OsJson.text(value, "profile");
			if (!dependencies().containsKey(OsJson.text(value, "fallback"))) throw new IllegalArgumentException("fallback_missing");
			if (value.has("reconsideration")) {
				var rule = value.getAsJsonObject("reconsideration"); OsJson.keys(rule, Set.of("fingerprint"), Set.of("fingerprint"));
				var keys = strings(rule.get("fingerprint"), 8);
				if (keys.isEmpty() || keys.stream().anyMatch(key -> !key.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}"))) throw new IllegalArgumentException("invalid_worker_reconsideration");
			}
		}
		if (examples().size() > 32) throw new IllegalArgumentException("example_limit");
		for (var example : examples()) {
			var item = OsJson.object(example);
			if (kind().equals("behavior")) {
				OsJson.keys(item, Set.of("input", "responses", "expected"), Set.of("input", "responses", "expected"));
				var responses = item.getAsJsonArray("responses"); var expected = item.getAsJsonArray("expected");
				if (responses.size() > 32 || expected.isEmpty() || expected.size() > 32 || expected.size() != responses.size() + (mode().equals("generator") ? 1 : 0)) throw new IllegalArgumentException("invalid_example");
			} else OsJson.keys(item, Set.of("input", "result"), Set.of("input", "result"));
		}
		digest = OsJson.digest(value);
	}
	public String digest() { return digest; }
	public String name() { return OsJson.text(value, "name"); }
	public String kind() { return OsJson.text(value, "kind"); }
	public String mode() { return OsJson.string(value, "mode", "generator"); }
	public String source() { return value.get("source").getAsString(); }
	public JsonContract input() { return input; }
	public JsonContract output() { return output; }
	public JsonObject value() { return value.deepCopy(); }
	public JsonArray examples() { return value.getAsJsonArray("examples").deepCopy(); }
	public Set<String> capabilities() { return strings(value.get("capabilities"), 64); }
	public Map<String, String> environment() { return stringMap("environment", false); }
	public Map<String, String> dependencies() { return stringMap("dependencies", true); }
	public static boolean isDigest(String value) { return value != null && value.matches("sha256:[0-9a-f]{64}"); }
	static Set<String> strings(JsonElement value, int limit) {
		if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > limit) throw new IllegalArgumentException("invalid_string_array");
		var result = new HashSet<String>();
		for (var item : value.getAsJsonArray()) {
			if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() || item.getAsString().isEmpty() || item.getAsString().length() > 256 || !result.add(item.getAsString())) throw new IllegalArgumentException("invalid_string_array");
		}
		return Set.copyOf(result);
	}
	private Map<String, String> stringMap(String field, boolean digests) {
		var object = OsJson.object(value.get(field)); var result = new LinkedHashMap<String, String>();
		if (object.size() > 32) throw new IllegalArgumentException("definition_limit");
		for (var entry : object.entrySet()) {
			String key = entry.getKey(), text = OsJson.text(object, key);
			if (!key.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,63}") || digests && !isDigest(text)) throw new IllegalArgumentException("invalid_dependency");
			result.put(key, text);
		}
		return Map.copyOf(result);
	}
	private void longText(String key, int maximum) {
		var item = value.get(key);
		if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() || item.getAsString().isBlank() || item.getAsString().getBytes(StandardCharsets.UTF_8).length > maximum) throw new IllegalArgumentException("definition_text_limit:" + key);
	}
}
