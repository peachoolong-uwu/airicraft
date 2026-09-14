package ai.moeru.airicraft.compat.journeymap;

import ai.moeru.airicraft.agent.memory.PlaceMemory;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Metadata lives on the native waypoint; unrelated JSON fields retain their values. */
final class JourneyMapLocationMetadata {
	static final String KEY = "airicraft:location";
	private static final Gson GSON = new Gson();
	record Value(String note, PlaceMemory.PreservedArea preserveArea) {}

	private JourneyMapLocationMetadata() {}

	static Value read(String customData) {
		JsonObject root = object(customData, false);
		if (root == null || !root.has(KEY)) return new Value("", null);
		try {
			JsonObject metadata = root.getAsJsonObject(KEY);
			if (integer(metadata, "version") != 1) throw new IllegalArgumentException("unsupported version");
			if (metadata.has("note") && (!metadata.get("note").isJsonPrimitive() || !metadata.getAsJsonPrimitive("note").isString())) {
				throw new IllegalArgumentException("note must be a string");
			}
			String note = metadata.has("note") ? metadata.get("note").getAsString() : "";
			PlaceMemory.checkedText(note, "note", 2048, true);
			PlaceMemory.PreservedArea area = null;
			if (metadata.has("preserveArea")) {
				JsonObject bounds = metadata.getAsJsonObject("preserveArea");
				area = new PlaceMemory.PreservedArea(integer(bounds, "x1"), integer(bounds, "y1"), integer(bounds, "z1"),
					integer(bounds, "x2"), integer(bounds, "y2"), integer(bounds, "z2"));
			}
			return new Value(note, area);
		}
		catch (RuntimeException exception) {
			throw new IllegalStateException("invalid_location_metadata: " + exception.getMessage(), exception);
		}
	}

	static String write(String existing, PlaceMemory.Place place) {
		JsonObject root = object(existing, true);
		// Validate owned data before replacing it; unknown versions must not be silently downgraded.
		read(existing);
		JsonObject metadata = root.has(KEY) ? root.getAsJsonObject(KEY).deepCopy() : new JsonObject();
		metadata.addProperty("version", 1);
		metadata.addProperty("note", place.note());
		metadata.remove("preserveArea");
		if (place.preserveArea() != null) metadata.add("preserveArea", GSON.toJsonTree(place.preserveArea()));
		root.add(KEY, metadata);
		return GSON.toJson(root);
	}

	private static int integer(JsonObject object, String key) {
		if (!object.has(key) || !object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isNumber()) {
			throw new IllegalArgumentException(key + " must be an integer");
		}
		return object.get(key).getAsBigDecimal().intValueExact();
	}

	private static JsonObject object(String raw, boolean writing) {
		if (raw == null || raw.isBlank()) return new JsonObject();
		try {
			var parsed = JsonParser.parseString(raw);
			if (parsed.isJsonObject()) return parsed.getAsJsonObject();
		}
		catch (RuntimeException exception) {
			if (raw.contains(KEY)) throw new IllegalStateException("invalid_location_metadata: malformed Airicraft data", exception);
		}
		if (writing) throw new IllegalStateException("location_metadata_conflict: cannot replace non-object custom data");
		return null;
	}
}
