package ai.moeru.airicraft.os;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Trusted domain adapter for the native container surface already implemented by the mod. */
public final class ContainerOperation {
	public record Prepared(JsonObject arguments, ResourceLedger.Observation observation, ResourceLedger.Bundle bundle, String source, String destination, int quantity) {
		public Prepared { arguments = arguments.deepCopy(); }
		@Override public JsonObject arguments() { return arguments.deepCopy(); }
	}
	public record Accounting(boolean released, boolean complete, Map<String, Integer> consumed, Map<String, Integer> produced) {}
	private final String scope, windowId;
	private final int priority;
	private final boolean retained;
	public ContainerOperation(String scope, String windowId, int priority, boolean retained) {
		if (!scope.matches("[^/\n]{1,64}") || windowId.isBlank() || windowId.length() > 256) throw new IllegalArgumentException("invalid_container_binding");
		this.scope = scope; this.windowId = windowId; this.priority = priority; this.retained = retained;
	}
	public String grant() { return "container:" + scope; }
	public String context() { return retained ? grant() : null; }
	public int priority() { return priority; }
	public JsonObject contextArguments(JsonObject frame) {
		var window = window(frame); return OsJson.obj("windowId", windowId, "syncId", window.get("syncId"));
	}
	public ResourceLedger.Observation observe(JsonObject frame, Set<String> known) {
		var stocks = inventory(frame, known); var capacities = new LinkedHashMap<String, Integer>(); var targets = new HashSet<String>();
		var window = frame.getAsJsonObject("facts").getAsJsonObject("window");
		if (window != null && OsJson.bool(window, "open", false) && windowId.equals(OsJson.string(window, "windowId", null))) {
			var slots = slots(window.getAsJsonArray("slots"), false); stocks.putAll(stocks(slots, true, grant(), known)); targets.add(grant());
			for (var value : slots) {
				var item = value.getAsJsonObject(); if (OsJson.number(item, "count", 0) == 0) continue;
				for (boolean side : new boolean[] {true, false}) capacities.put(resource(side ? grant() : "player", item.get("itemId").getAsString(), item.get("variant").getAsString()), capacity(slots, side, item));
			}
		}
		return new ResourceLedger.Observation(OsJson.text(frame, "epoch"), OsJson.text(frame, "captureId"), stocks, Map.of(), capacities, targets);
	}
	public Prepared prepare(JsonObject frame, JsonObject args) {
		OsJson.keys(args, Set.of("direction", "itemId", "quantity"), Set.of("direction", "itemId", "quantity"));
		String direction = OsJson.text(args, "direction"), itemId = OsJson.text(args, "itemId"); int quantity = OsConfiguration.integer(args, "quantity", 0, 64);
		if (!Set.of("withdraw", "deposit").contains(direction) || quantity < 1) throw new IllegalArgumentException("invalid_transfer");
		if (!frame.getAsJsonObject("facts").getAsJsonArray("supportedItems").contains(OsJson.json(itemId))) throw new IllegalArgumentException("unsupported_transfer_item");
		var window = window(frame); var slots = slots(window.getAsJsonArray("slots"), false); boolean from = direction.equals("withdraw");
		var sources = slots.asList().stream().map(value -> value.getAsJsonObject()).filter(slot -> OsJson.bool(slot, "container", false) == from && OsJson.number(slot, "count", 0) > 0 && itemId.equals(slot.get("itemId").getAsString())).toList();
		if (sources.isEmpty()) throw new IllegalStateException("resource_unavailable");
		var item = sources.getFirst(); String variant = item.get("variant").getAsString();
		if (sources.stream().anyMatch(slot -> !slot.get("variant").equals(item.get("variant")) || !slot.get("maxCount").equals(item.get("maxCount")))) throw new IllegalStateException("ambiguous_item_components");
		String source = resource(from ? grant() : "player", itemId, variant), destination = resource(from ? "player" : grant(), itemId, variant);
		var stocks = stocks(slots, true, grant(), Set.of(source, destination)); stocks.putAll(stocks(slots, false, "player", Set.of(source, destination)));
		var observation = new ResourceLedger.Observation(OsJson.text(frame, "epoch"), OsJson.text(frame, "captureId"), stocks, Map.of(), Map.of(destination, capacity(slots, !from, item)), Set.of(grant()));
		var bundle = new ResourceLedger.Bundle(Map.of(source, quantity), Map.of(), Map.of(destination, quantity), Set.of(grant()));
		var nativeArgs = args.deepCopy(); nativeArgs.addProperty("windowId", windowId); nativeArgs.add("syncId", window.get("syncId"));
		nativeArgs.add("allowance", OsJson.obj("sourceItems", quantity, "destinationItems", quantity));
		return new Prepared(nativeArgs, observation, bundle, source, destination, quantity);
	}
	public Accounting account(Prepared prepared, JsonObject receipt) {
		boolean released = NativeEffects.released(receipt);
		if (receipt.has("disposition") || "admission_rejected".equals(OsJson.string(receipt, "phase", null))) return new Accounting(released, released, Map.of(), Map.of());
		var effects = receipt.getAsJsonObject("effects");
		if (!effects.has("transferred") && !released) return new Accounting(false, false, Map.of(), Map.of());
		int moved = OsConfiguration.integer(effects, "transferred", 0, 64);
		if (OsConfiguration.integer(effects, "quantity", 0, 64) != prepared.quantity || moved > prepared.quantity || OsConfiguration.integer(effects, "remaining", 0, 64) != prepared.quantity - moved) throw new IllegalStateException("invalid_transfer_accounting");
		return new Accounting(released, OsJson.bool(effects, "accountingComplete", false), Map.of(prepared.source, moved), Map.of(prepared.destination, moved));
	}
	public static String resource(String location, String itemId, String variant) {
		if ((!location.equals("player") && !location.matches("container:[^/\n]{1,64}")) || itemId.isBlank() || itemId.length() > 128 || variant.length() > 4096) throw new IllegalArgumentException("invalid_item_identity");
		try { return location + "/" + itemId + "/" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(variant.getBytes(StandardCharsets.UTF_8))); }
		catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
	}
	public static Map<String, Integer> inventory(JsonObject frame, Set<String> known) {
		var inventory = frame.getAsJsonObject("facts").getAsJsonObject("inventory");
		return inventory != null && OsJson.bool(inventory, "available", false) ? stocks(slots(inventory.getAsJsonArray("slots"), true), false, "player", known) : new LinkedHashMap<>();
	}
	private JsonObject window(JsonObject frame) {
		var window = frame.getAsJsonObject("facts").getAsJsonObject("window");
		if (window == null || !OsJson.bool(window, "open", false) || !windowId.equals(OsJson.string(window, "windowId", null)) || !window.has("syncId") || OsJson.number(window.getAsJsonObject("cursor"), "count", 0) != 0) throw new IllegalStateException("container_changed");
		return window;
	}
	private static JsonArray slots(JsonArray slots, boolean inventory) {
		if (slots.size() > 90 || inventory && slots.size() != 36) throw new IllegalArgumentException("invalid_inventory_observation");
		var ids = new HashSet<Long>();
		for (var value : slots) {
			var slot = value.getAsJsonObject(); long id = OsJson.number(slot, "id", -1);
			long count = OsJson.number(slot, "count", 0), maximum = OsJson.number(slot, "maxCount", 0);
			if (id < 0 || !ids.add(id) || count > maximum || maximum < 1 || maximum > 64 || inventory && (id > 35 || OsJson.bool(slot, "container", false))) throw new IllegalArgumentException("invalid_inventory_observation");
			if (count > 0) OsJson.text(slot, "itemId");
			if (slot.get("variant").getAsString().length() > 4096) throw new IllegalArgumentException("invalid_item_variant");
		}
		return slots;
	}
	private static Map<String, Integer> stocks(JsonArray slots, boolean side, String location, Set<String> known) {
		var stocks = new LinkedHashMap<String, Integer>(); known.stream().filter(key -> key.startsWith(location + "/")).forEach(key -> stocks.put(key, 0));
		for (var value : slots) {
			var slot = value.getAsJsonObject(); int count = slot.get("count").getAsInt();
			if (OsJson.bool(slot, "container", false) == side && count > 0) stocks.merge(resource(location, slot.get("itemId").getAsString(), slot.get("variant").getAsString()), count, Integer::sum);
		}
		return stocks;
	}
	private static int capacity(JsonArray slots, boolean side, JsonObject item) {
		int capacity = 0;
		for (var value : slots) {
			var slot = value.getAsJsonObject(); if (OsJson.bool(slot, "container", false) != side) continue;
			int count = slot.get("count").getAsInt();
			if (count == 0) capacity += item.get("maxCount").getAsInt();
			else if (slot.get("itemId").equals(item.get("itemId")) && slot.get("variant").equals(item.get("variant"))) capacity += slot.get("maxCount").getAsInt() - count;
		}
		return capacity;
	}
}
