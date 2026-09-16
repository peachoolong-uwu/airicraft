package ai.moeru.airicraft.os;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** Copied JSON is the only value that crosses the skill interface. */
public final class OsJson {
	public static final int MESSAGE_BYTES = 16_384;
	private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
	private OsJson() {}
	public static JsonElement copy(JsonElement value) { return copy(value, MESSAGE_BYTES, 16, 2048); }
	public static JsonElement copy(JsonElement value, int bytes, int depth, int nodes) {
		if (value == null) return JsonNull.INSTANCE;
		validate(value, 0, depth, new int[] {nodes});
		String encoded = JSON.toJson(value);
		if (encoded.getBytes(StandardCharsets.UTF_8).length > bytes) throw new IllegalArgumentException("message_limit");
		return value.deepCopy();
	}
	public static JsonElement parse(String value) {
		return parse(value, MESSAGE_BYTES, 16, 2048);
	}
	public static JsonElement parse(String value, int bytes, int depth, int nodes) {
		if (value == null || value.getBytes(StandardCharsets.UTF_8).length > bytes) throw new IllegalArgumentException("message_limit");
		int nesting = 0; boolean quoted = false, escaped = false;
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (quoted) {
				if (escaped) escaped = false;
				else if (character == '\\') escaped = true;
				else if (character == '"') quoted = false;
			} else if (character == '"') quoted = true;
			else if ((character == '[' || character == '{') && ++nesting > depth + 1) throw new IllegalArgumentException("message_limit");
			else if (character == ']' || character == '}') nesting--;
		}
		return copy(JsonParser.parseString(value), bytes, depth, nodes);
	}
	public static String encode(JsonElement value) { return JSON.toJson(copy(value)); }
	public static String canonical(JsonElement value) { return JSON.toJson(sorted(value)); }
	public static String digest(JsonElement value) {
		try {
			return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
	}
	public static JsonElement json(Object value) { return JSON.toJsonTree(value); }
	public static JsonObject obj(Object... pairs) {
		if (pairs.length % 2 != 0) throw new IllegalArgumentException("invalid_pairs");
		var result = new JsonObject();
		for (int i = 0; i < pairs.length; i += 2) result.add((String) pairs[i], json(pairs[i + 1]));
		return result;
	}
	public static String string(JsonObject value, String key, String fallback) {
		return !value.has(key) || value.get(key).isJsonNull() ? fallback : text(value, key);
	}
	public static long number(JsonObject value, String key, long fallback) {
		if (!value.has(key)) return fallback;
		var item = value.get(key);
		if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("integer_required:" + key);
		double number = item.getAsDouble();
		if (!Double.isFinite(number) || number < 0 || number > 9_007_199_254_740_991L || Math.rint(number) != number) throw new IllegalArgumentException("invalid_integer:" + key);
		return item.getAsLong();
	}
	public static boolean bool(JsonObject value, String key, boolean fallback) {
		if (!value.has(key)) return fallback;
		var item = value.get(key);
		if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("boolean_required:" + key);
		return item.getAsBoolean();
	}
	public static String reason(Throwable error) {
		while (error.getCause() != null && (error instanceof java.util.concurrent.CompletionException || error instanceof java.util.concurrent.ExecutionException)) error = error.getCause();
		String reason = error.getMessage();
		return reason == null ? error.getClass().getSimpleName() : reason.substring(0, Math.min(reason.length(), 256));
	}
	public static JsonObject object(JsonElement value) {
		if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("object_required");
		return value.getAsJsonObject();
	}
	public static String text(JsonObject value, String key) {
		var item = value.get(key);
		if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("text_required:" + key);
		String text = item.getAsString();
		if (text.isEmpty() || text.length() > 256) throw new IllegalArgumentException("invalid_name:" + key);
		return text;
	}
	public static void keys(JsonObject value, Set<String> allowed, Set<String> required) {
		if (!allowed.containsAll(value.keySet()) || !value.keySet().containsAll(required)) throw new IllegalArgumentException("invalid_fields");
	}
	private static void validate(JsonElement value, int level, int depth, int[] left) {
		if (level > depth || --left[0] < 0) throw new IllegalArgumentException("message_limit");
		if (value.isJsonArray()) for (var child : value.getAsJsonArray()) validate(child, level + 1, depth, left);
		else if (value.isJsonObject()) for (var entry : value.getAsJsonObject().entrySet()) validate(entry.getValue(), level + 1, depth, left);
		else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && !Double.isFinite(value.getAsDouble()))
			throw new IllegalArgumentException("invalid_json_number");
	}
	private static JsonElement sorted(JsonElement value) {
		if (value == null || value.isJsonNull()) return JsonNull.INSTANCE;
		if (value.isJsonArray()) {
			var result = new JsonArray(); for (var child : value.getAsJsonArray()) result.add(sorted(child)); return result;
		}
		if (value.isJsonObject()) {
			var result = new JsonObject();
			value.getAsJsonObject().keySet().stream().sorted().forEach(key -> result.add(key, sorted(value.getAsJsonObject().get(key))));
			return result;
		}
		return value.deepCopy();
	}
}
