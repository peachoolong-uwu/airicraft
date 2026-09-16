package ai.moeru.airicraft.os;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** A closed, bounded JSON Schema subset. Unsupported constraints are rejected. */
public final class JsonContract {
	private static final Map<String, Set<String>> KEYWORDS = Map.of(
		"null", Set.of(), "boolean", Set.of(), "number", Set.of("minimum", "maximum"),
		"integer", Set.of("minimum", "maximum"), "string", Set.of("minLength", "maxLength"),
		"array", Set.of("items", "minItems", "maxItems"), "object", Set.of("properties", "required", "additionalProperties"));
	private interface Rule { void check(JsonElement value); }
	private final Rule rule;
	public JsonContract(JsonElement schema) { rule = compile(OsJson.copy(schema), 0, new int[] {256}); }
	public JsonElement check(JsonElement value) {
		var copy = OsJson.copy(value); rule.check(copy); return copy;
	}
	private static Rule compile(JsonElement value, int depth, int[] nodes) {
		if (depth > 8 || --nodes[0] < 0) throw new IllegalArgumentException("contract_limit");
		if (value == null || value.isJsonNull()) throw new IllegalArgumentException("invalid_contract");
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) return item -> { if (!value.getAsBoolean()) mismatch(); };
		JsonObject schema = OsJson.object(value);
		String type = OsJson.text(schema, "type");
		if (!KEYWORDS.containsKey(type)) throw new IllegalArgumentException("invalid_contract");
		var allowed = new HashSet<>(KEYWORDS.get(type)); allowed.addAll(Set.of("type", "enum"));
		OsJson.keys(schema, allowed, Set.of("type"));
		Set<String> choices = new HashSet<>();
		if (schema.has("enum")) {
			var values = schema.getAsJsonArray("enum");
			if (values.isEmpty() || values.size() > 32) throw new IllegalArgumentException("invalid_contract");
			values.forEach(item -> choices.add(OsJson.canonical(item)));
		}
		String minKey = type.equals("string") ? "minLength" : type.equals("array") ? "minItems" : "minimum";
		String maxKey = type.equals("string") ? "maxLength" : type.equals("array") ? "maxItems" : "maximum";
		Double minimum = bound(schema, minKey, type), maximum = bound(schema, maxKey, type);
		if (minimum != null && maximum != null && minimum > maximum) throw new IllegalArgumentException("invalid_contract");
		Rule items = type.equals("array") ? compile(schema.get("items"), depth + 1, nodes) : null;
		var properties = new LinkedHashMap<String, Rule>();
		var required = new HashSet<String>();
		boolean additional = OsJson.bool(schema, "additionalProperties", false);
		if (type.equals("object")) {
			if (schema.has("properties")) {
				var definitions = OsJson.object(schema.get("properties"));
				if (definitions.size() > 64) throw new IllegalArgumentException("contract_limit");
				for (var entry : definitions.entrySet()) {
					if (entry.getKey().length() > 128) throw new IllegalArgumentException("invalid_contract");
					properties.put(entry.getKey(), compile(entry.getValue(), depth + 1, nodes));
				}
			}
			if (schema.has("required")) for (var item : schema.getAsJsonArray("required")) {
				if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() || !properties.containsKey(item.getAsString()) || !required.add(item.getAsString()))
					throw new IllegalArgumentException("invalid_contract");
			}
		}
		return item -> {
			boolean matches = switch (type) {
				case "null" -> item.isJsonNull();
				case "object" -> item.isJsonObject();
				case "array" -> item.isJsonArray();
				case "boolean" -> item.isJsonPrimitive() && item.getAsJsonPrimitive().isBoolean();
				case "string" -> item.isJsonPrimitive() && item.getAsJsonPrimitive().isString();
				case "integer" -> item.isJsonPrimitive() && item.getAsJsonPrimitive().isNumber() && Math.rint(item.getAsDouble()) == item.getAsDouble() && Math.abs(item.getAsDouble()) <= 9_007_199_254_740_991L;
				case "number" -> item.isJsonPrimitive() && item.getAsJsonPrimitive().isNumber();
				default -> false;
			};
			if (!matches || !choices.isEmpty() && !choices.contains(OsJson.canonical(item))) mismatch();
			if (minimum != null || maximum != null) {
				double measured = type.equals("array") ? item.getAsJsonArray().size() : type.equals("string") ? item.getAsString().codePointCount(0, item.getAsString().length()) : item.getAsDouble();
				if (minimum != null && measured < minimum || maximum != null && measured > maximum) mismatch();
			}
			if (items != null) item.getAsJsonArray().forEach(items::check);
			if (type.equals("object")) {
				if (!item.getAsJsonObject().keySet().containsAll(required)) mismatch();
				for (var entry : item.getAsJsonObject().entrySet()) {
					var validator = properties.get(entry.getKey());
					if (validator != null) validator.check(entry.getValue()); else if (!additional) mismatch();
				}
			}
		};
	}
	private static Double bound(JsonObject schema, String key, String type) {
		if (!schema.has(key)) return null;
		var value = schema.get(key);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble())) throw new IllegalArgumentException("invalid_contract");
		double number = value.getAsDouble();
		if (Set.of("string", "array").contains(type) && (Math.rint(number) != number || number < 0 || number > (type.equals("string") ? 16_384 : 2048))) throw new IllegalArgumentException("invalid_contract");
		return number;
	}
	private static void mismatch() { throw new IllegalArgumentException("contract_mismatch"); }
}
