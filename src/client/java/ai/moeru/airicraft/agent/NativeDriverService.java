package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.llm.PlannerToolCatalog;
import ai.moeru.airicraft.os.NativeActionRuntime;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

import static ai.moeru.airicraft.os.NativeActionRuntime.*;

/** Versioned driver-only JSON facade. No guest receives access to this transport. */
public final class NativeDriverService {
	private static final Gson JSON = new GsonBuilder().serializeNulls().create();
	private static final Set<String> NAMES = Set.of("os_observe", "os_lease", "os_submit", "os_inspect", "os_cancel");
	private final NativeActionRuntime runtime;
	private final LongSupplier clock;
	private final LongSupplier clientTick;
	private final LongSupplier serverTick;
	private boolean activated;

	public NativeDriverService(NativeActionRuntime runtime, LongSupplier clock, LongSupplier clientTick, LongSupplier serverTick) {
		this.runtime = runtime; this.clock = clock; this.clientTick = clientTick; this.serverTick = serverTick;
	}
	public static boolean handles(String name) { return NAMES.contains(name); }
	public void tick() { runtime.tick(); }
	public boolean ownsPlayer() { return runtime.blocksOrdinaryActions(); }
	public boolean activated() { return activated; }
	public void interrupt(String reason) { runtime.interrupt(reason); }
	public boolean allowsOrdinaryTool(String name) {
		String normalized = PlannerToolCatalog.normalizeName(name);
		if (PlannerToolCatalog.CANCEL_TASK.equals(normalized) || PlannerToolCatalog.CONFIGURE_REFLEX.equals(normalized)) return true;
		if (PlannerToolCatalog.isReadTool(normalized) && !PlannerToolCatalog.TAKE_A_LOOK.equals(normalized)) return true;
		if (Set.of("inspect_work", "list_work", "wait_for_work", "recall_place", "list_places", "search_recipes", "read_logbook").contains(normalized)) return true;
		return !ownsPlayer();
	}

	public JsonObject execute(String name, JsonObject args) {
		var result = new JsonObject();
		result.addProperty("schemaVersion", 1);
		try {
			validateEnvelope(args);
			result.addProperty("status", "ok");
			switch (name) {
				case "os_observe" -> {
					keys(args, Set.of("progressScopes"));
					var observation = runtime.observe(args);
					var frame = JSON.toJsonTree(observation).getAsJsonObject();
					frame.addProperty("schemaVersion", 1);
					frame.addProperty("capturedAtNanos", Long.toString(observation.capturedAtNanos()));
					frame.addProperty("captureAgeMillis", Math.max(0, clock.getAsLong() - observation.capturedAtNanos()) / 1_000_000.0);
					frame.addProperty("clockDomain", "native:" + observation.sessionId() + ":System.nanoTime");
					frame.addProperty("source", "native_client_observation");
					frame.addProperty("clientTick", clientTick.getAsLong());
					long tick = serverTick.getAsLong();
					frame.add("serverTick", tick < 0 ? com.google.gson.JsonNull.INSTANCE : JSON.toJsonTree(tick));
					result.add("frame", frame);
				}
				case "os_lease" -> {
					keys(args, Set.of("action", "hostId", "epoch", "lease"));
					switch (string(args, "action")) {
						case "acquire" -> {
							result.add("lease", JSON.toJsonTree(runtime.acquire(string(args, "hostId"), string(args, "epoch"))));
							activated = true;
						}
						case "heartbeat" -> result.add("lease", JSON.toJsonTree(runtime.heartbeat(lease(object(args, "lease")))));
						case "release" -> runtime.release(lease(object(args, "lease")));
						default -> throw new Rejected("invalid_request");
					}
				}
				case "os_submit" -> {
					keys(args, Set.of("schemaVersion", "id", "captureId", "operation", "arguments", "payloadHash"));
					if (integer(args, "schemaVersion", 1, 1000) != 1) throw new Rejected("unsupported_schema");
					var request = new Request(id(object(args, "id")), string(args, "captureId"), string(args, "operation"),
						object(args, "arguments"), string(args, "payloadHash"));
					result.add("receipt", JSON.toJsonTree(runtime.submit(request)));
				}
				case "os_inspect" -> {
					if (args.has("id")) {
						keys(args, Set.of("id"));
						result.add("receipt", JSON.toJsonTree(runtime.inspect(id(object(args, "id")))));
					} else {
						keys(args, Set.of("sessionId", "sinceSeqNo", "limit"));
						if (!string(args, "sessionId").equals(runtime.authority().sessionId())) throw new Rejected("stale_session");
						result.add("history", JSON.toJsonTree(runtime.history(integer(args, "sinceSeqNo", 0, 9_007_199_254_740_991L),
							args.has("limit") ? (int) integer(args, "limit", 1, 32) : 16)));
					}
				}
				case "os_cancel" -> {
					keys(args, Set.of("lease", "id"));
					result.add("receipt", JSON.toJsonTree(runtime.cancel(lease(object(args, "lease")), id(object(args, "id")))));
				}
				default -> throw new Rejected("unknown_native_method");
			}
		} catch (Rejected rejected) {
			result.addProperty("status", "rejected");
			result.addProperty("code", rejected.code());
		} catch (IllegalArgumentException | IllegalStateException exception) {
			result.addProperty("status", "rejected");
			result.addProperty("code", "invalid_request");
		}
		result.add("authority", JSON.toJsonTree(runtime.authority()));
		return result;
	}

	static void validateEnvelope(JsonObject args) {
		if (args == null) throw new Rejected("invalid_request");
		validateValue(args, 0, new int[2]);
		if (args.toString().getBytes(StandardCharsets.UTF_8).length > 16_384) throw new Rejected("invalid_request");
	}
	private static void validateValue(JsonElement value, int depth, int[] budget) {
		if (depth > 16 || ++budget[0] > 2048) throw new Rejected("invalid_request");
		if (value.isJsonObject()) {
			for (var entry : value.getAsJsonObject().entrySet()) {
				budget[1] += entry.getKey().length();
				if (budget[1] > 16_384) throw new Rejected("invalid_request");
				validateValue(entry.getValue(), depth + 1, budget);
			}
		} else if (value.isJsonArray()) {
			for (var child : value.getAsJsonArray()) validateValue(child, depth + 1, budget);
		} else if (value.isJsonPrimitive()) {
			budget[1] += value.getAsString().length();
			if (budget[1] > 16_384) throw new Rejected("invalid_request");
		}
	}

	private static Lease lease(JsonObject value) {
		keys(value, Set.of("epoch", "generation", "hostId"));
		return new Lease(string(value, "epoch"), integer(value, "generation", 1, 9_007_199_254_740_991L), string(value, "hostId"));
	}
	private static Id id(JsonObject value) {
		keys(value, Set.of("epoch", "generation", "sequence"));
		return new Id(string(value, "epoch"), integer(value, "generation", 1, 9_007_199_254_740_991L), integer(value, "sequence", 1, 9_007_199_254_740_991L));
	}
	private static void keys(JsonObject value, Set<String> allowed) {
		if (!allowed.containsAll(value.keySet())) throw new Rejected("invalid_request");
	}
	private static JsonObject object(JsonObject value, String key) {
		JsonElement child = value.get(key);
		if (child == null || !child.isJsonObject()) throw new Rejected("invalid_request");
		return child.getAsJsonObject();
	}
	private static String string(JsonObject value, String key) {
		JsonElement child = value.get(key);
		if (child == null || !child.isJsonPrimitive() || !child.getAsJsonPrimitive().isString()) throw new Rejected("invalid_request");
		String text = child.getAsString();
		if (text.isBlank() || text.length() > 256) throw new Rejected("invalid_request");
		return text;
	}
	private static long integer(JsonObject value, String key, long minimum, long maximum) {
		try {
			JsonElement child = value.get(key);
			if (child == null || !child.isJsonPrimitive() || !child.getAsJsonPrimitive().isNumber()) throw new Rejected("invalid_request");
			long number = child.getAsBigDecimal().longValueExact();
			if (number < minimum || number > maximum) throw new Rejected("invalid_request");
			return number;
		} catch (ArithmeticException exception) { throw new Rejected("invalid_request"); }
	}

	public static List<Map<String, Object>> tools() {
		var text = Map.<String, Object>of("type", "string", "maxLength", 256);
		var number = Map.<String, Object>of("type", "integer", "minimum", 1, "maximum", 9_007_199_254_740_991L);
		var id = schema(Map.of("epoch", text, "generation", number, "sequence", number));
		var lease = schema(Map.of("epoch", text, "generation", number, "hostId", text));
		var bounded = Map.<String, Object>of("type", "integer", "minimum", 1, "maximum", 64);
		var binding = schema(Map.of("windowId", text, "syncId", Map.of("type", "integer", "minimum", 0)));
		var transferProperties = new java.util.LinkedHashMap<String, Object>(Map.of("windowId", text, "syncId", Map.of("type", "integer", "minimum", 0),
			"direction", Map.of("type", "string", "enum", List.of("deposit", "withdraw")), "itemId", text, "quantity", bounded,
			"allowance", schema(Map.of("sourceItems", bounded, "destinationItems", bounded))));
		var requiredTransfer = transferProperties.keySet().stream().sorted().toList();
		transferProperties.put("contextId", text);
		var transfer = Map.of("type", "object", "properties", transferProperties, "required", requiredTransfer, "additionalProperties", false);
		return List.of(
			PlannerToolCatalog.toolForProvider("os_observe", "Read passive inventory, the current open container, native authority and registered progress counters. Optional progressScopes replaces up to 32 scoped chunk/entity clocks; omitted retains them. Never opens a GUI or forces chunks to load.",
				Map.of("progressScopes", Map.of("type", "array", "maxItems", 32, "items", Map.of("type", "object", "properties", Map.of(
					"scope", text, "chunks", Map.of("type", "array", "minItems", 1, "maxItems", 16, "items", schema(Map.of("x", Map.of("type", "integer"), "z", Map.of("type", "integer")))),
					"entities", Map.of("type", "array", "minItems", 1, "maxItems", 16, "items", text)), "required", List.of("scope"), "additionalProperties", false))), List.of()),
			PlannerToolCatalog.toolForProvider("os_lease", "Acquire the free player, renew each second, or request release. Five seconds without renewal revokes the fence; cleanup must still settle.",
				Map.of("action", Map.of("type", "string", "enum", List.of("acquire", "heartbeat", "release")), "hostId", text, "epoch", text, "lease", lease), List.of("action")),
			PlannerToolCatalog.toolForProvider("os_submit", "Admit a bounded transfer or retain an already-open container. Retained transfers require the returned contextId. A released transfer does not release its retained context; cancel the context ID to close it. Query the same request ID after a lost response. Acceptance is not completion.",
				Map.of("schemaVersion", Map.of("type", "integer", "const", 1), "id", id, "captureId", text, "operation", Map.of("type", "string", "enum", List.of("transfer_container", "retain_container")),
					"arguments", Map.of("oneOf", List.of(binding, transfer)), "payloadHash", text), List.of("schemaVersion", "id", "captureId", "operation", "arguments", "payloadHash")),
			PlannerToolCatalog.toolForProvider("os_inspect", "Query either an exact id, or ordered history with sessionId, sinceSeqNo and optional limit (default 16, max 32). Gaps and outcome_unknown never authorize replay.",
				Map.of("id", id, "sessionId", text, "sinceSeqNo", Map.of("type", "integer", "minimum", 0), "limit", Map.of("type", "integer", "minimum", 1, "maximum", 32)), List.of()),
			PlannerToolCatalog.toolForProvider("os_cancel", "Cancel an exact operation or retained context. Context cancellation drains its current operation before closing. Retain ownership until every owned receipt has released=true; partial effects remain in the world.", Map.of("lease", lease, "id", id), List.of("lease", "id"))
		);
	}
	private static Map<String, Object> schema(Map<String, Object> properties) {
		return Map.of("type", "object", "properties", properties, "required", properties.keySet().stream().sorted().toList(), "additionalProperties", false);
	}
}
