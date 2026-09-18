package ai.moeru.airicraft.agent.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;

import java.util.ArrayList;
import java.util.List;

/** Decodes one extra JSON string layer at schema-declared object/array boundaries. Not a validator. */
final class PlannerJsonRepair {
	private static final Gson STRICT_JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();

	record Result(JsonElement value, List<String> paths) {}

	static Result repair(JsonElement value, JsonObject schema) {
		List<String> paths = new ArrayList<>();
		JsonElement repaired = visit(value.deepCopy(), schema, "", paths);
		return new Result(repaired, List.copyOf(paths));
	}

	private static JsonElement visit(JsonElement value, JsonObject schema, String path, List<String> paths) {
		List<JsonObject> alternatives = alternatives(schema);
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
			JsonElement string = value;
			// A legitimate string alternative (including unrestricted text) always wins.
			if (alternatives.stream().anyMatch(option -> allowsString(option, string))) return value;
			if (alternatives.stream().noneMatch(option -> hasType(option, "object") || hasType(option, "array"))) return value;
			JsonElement decoded;
			try { decoded = STRICT_JSON.fromJson(value.getAsString(), JsonElement.class); }
			catch (JsonParseException exception) { return value; }
			if (decoded == null || (!decoded.isJsonObject() && !decoded.isJsonArray())) return value;
			String type = decoded.isJsonObject() ? "object" : "array";
			if (alternatives.stream().filter(option -> hasType(option, type)).count() != 1) return value;
			value = decoded;
			paths.add(path);
		}
		String type = value.isJsonObject() ? "object" : value.isJsonArray() ? "array" : "";
		List<JsonObject> matching = alternatives.stream().filter(option -> hasType(option, type)).toList();
		// Do not guess between multiple structural alternatives.
		if (matching.size() != 1) return value;
		JsonObject selected = matching.getFirst();
		if (value.isJsonObject()) {
			JsonObject object = value.getAsJsonObject();
			JsonObject properties = selected.has("properties") ? selected.getAsJsonObject("properties") : new JsonObject();
			for (String key : List.copyOf(object.keySet())) {
				JsonElement childSchema = properties.has(key) ? properties.get(key) : selected.get("additionalProperties");
				if (childSchema != null && childSchema.isJsonObject()) {
					String pointer = path + "/" + key.replace("~", "~0").replace("/", "~1");
					object.add(key, visit(object.get(key), childSchema.getAsJsonObject(), pointer, paths));
				}
			}
		} else if (value.isJsonArray() && selected.has("items") && selected.get("items").isJsonObject()) {
			var array = value.getAsJsonArray();
			for (int i = 0; i < array.size(); i++) {
				array.set(i, visit(array.get(i), selected.getAsJsonObject("items"), path + "/" + i, paths));
			}
		}
		return value;
	}

	private static List<JsonObject> alternatives(JsonObject schema) {
		for (String union : List.of("oneOf", "anyOf")) {
			if (schema.has(union)) {
				List<JsonObject> result = new ArrayList<>();
				for (JsonElement option : schema.getAsJsonArray(union)) result.addAll(alternatives(option.getAsJsonObject()));
				return result;
			}
		}
		return List.of(schema);
	}

	private static boolean hasType(JsonObject schema, String type) {
		JsonElement declared = schema.get("type");
		if (declared == null) return false;
		if (declared.isJsonArray()) {
			for (JsonElement member : declared.getAsJsonArray()) if (type.equals(member.getAsString())) return true;
			return false;
		}
		return type.equals(declared.getAsString());
	}

	private static boolean allowsString(JsonObject schema, JsonElement value) {
		// An unconstrained branch can accept text; don't reinterpret it.
		if (schema.has("type") && !hasType(schema, "string")) return false;
		if (schema.has("const") && !schema.get("const").equals(value)) return false;
		if (schema.has("enum")) {
			for (JsonElement allowed : schema.getAsJsonArray("enum")) if (allowed.equals(value)) return true;
			return false;
		}
		return true;
	}
}
