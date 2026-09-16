package ai.moeru.airicraft.os;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Operator-owned configuration. Skills cannot add operations, priorities or capabilities. */
public final class OsConfiguration {
	public record Resource(String key, String grant, Set<String> methods, int priority) { public Resource { methods = Set.copyOf(methods); } }
	public record View(String location, Map<String, String> resources) { public View { resources = Map.copyOf(resources); } }
	public record Supply(String resource, String operation, JsonObject arguments, int maximum) {
		public Supply { arguments = arguments.deepCopy(); }
		@Override public JsonObject arguments() { return arguments.deepCopy(); }
	}
	public final Map<String, ContainerOperation> operations;
	public final Map<String, Resource> resources;
	public final Map<String, View> views;
	public final Map<String, Supply> supplies;
	public final Set<String> grants;
	public final Map<String, String> environment = Map.of("minecraft", "1.21.8", "host", "mod-java");
	private final JsonArray progressScopes;
	private final JsonObject workerProfiles;
	public OsConfiguration(JsonObject configuration, JsonObject initialFrame) {
		var input = OsJson.object(OsJson.copy(configuration));
		OsJson.keys(input, Set.of("bindOpenContainer", "operations", "resources", "views", "supplies", "progressScopes", "workerProfiles"), Set.of());
		var operations = new LinkedHashMap<String, ContainerOperation>();
		if (OsJson.bool(input, "bindOpenContainer", false)) {
			var window = initialFrame.getAsJsonObject("facts").getAsJsonObject("window");
			if (!OsJson.bool(window, "open", false)) throw new IllegalArgumentException("open_container_required");
			operations.put("chest", new ContainerOperation("home", OsJson.text(window, "windowId"), 10, true));
		}
		for (var entry : map(input, "operations").entrySet()) {
			var value = OsJson.object(entry.getValue());
			OsJson.keys(value, Set.of("scope", "windowId", "priority", "context"), Set.of("scope", "windowId"));
			operations.put(entry.getKey(), new ContainerOperation(OsJson.text(value, "scope"), OsJson.text(value, "windowId"), integer(value, "priority", 0, Integer.MAX_VALUE), OsJson.bool(value, "context", true)));
		}
		this.operations = Map.copyOf(operations);
		var resources = new LinkedHashMap<String, Resource>();
		for (var entry : map(input, "resources").entrySet()) {
			var value = OsJson.object(entry.getValue());
			OsJson.keys(value, Set.of("key", "grant", "methods", "priority"), Set.of("key", "grant", "methods"));
			var methods = SkillDefinition.strings(value.get("methods"), 32);
			if (!operations.keySet().containsAll(methods)) throw new IllegalArgumentException("supply_operation_unknown");
			resources.put(entry.getKey(), new Resource(OsJson.text(value, "key"), OsJson.text(value, "grant"), methods, integer(value, "priority", 0, Integer.MAX_VALUE)));
		}
		this.resources = Map.copyOf(resources);
		var views = new LinkedHashMap<String, View>(); views.put("inventory", new View("player", Map.of()));
		for (var entry : map(input, "views").entrySet()) {
			var value = OsJson.object(entry.getValue()); OsJson.keys(value, Set.of("location", "resources"), Set.of("location", "resources"));
			String location = OsJson.text(value, "location");
			if (!location.equals("player") && operations.values().stream().noneMatch(operation -> operation.grant().equals(location))) throw new IllegalArgumentException("view_location_unknown");
			var names = new LinkedHashMap<String, String>();
			for (var resource : map(value, "resources").entrySet()) {
				String key = OsJson.text(value.getAsJsonObject("resources"), resource.getKey());
				if (!key.startsWith(location + "/")) throw new IllegalArgumentException("view_location_mismatch");
				names.put(resource.getKey(), key);
			}
			views.put(entry.getKey(), new View(location, names));
		}
		this.views = Map.copyOf(views);
		var supplies = new LinkedHashMap<String, Supply>();
		for (var entry : map(input, "supplies").entrySet()) {
			var value = OsJson.object(entry.getValue()); OsJson.keys(value, Set.of("resource", "operation", "arguments", "maximum"), Set.of("resource", "operation", "arguments", "maximum"));
			String resource = OsJson.text(value, "resource"), operation = OsJson.text(value, "operation");
			var arguments = OsJson.object(value.get("arguments")); int maximum = integer(value, "maximum", 0, 64);
			if (!resources.containsKey(resource) || !resources.get(resource).methods.contains(operation) || !operations.containsKey(operation) || arguments.has("quantity") || maximum < 1) throw new IllegalArgumentException("invalid_supply_rule");
			supplies.put(entry.getKey(), new Supply(resource, operation, arguments, maximum));
		}
		this.supplies = Map.copyOf(supplies);
		progressScopes = input.has("progressScopes") ? input.getAsJsonArray("progressScopes").deepCopy() : new JsonArray();
		if (progressScopes.size() + views.size() > 32) throw new IllegalArgumentException("scope_capacity");
		var grants = new LinkedHashSet<String>();
		operations.values().forEach(operation -> grants.add(operation.grant())); resources.values().forEach(resource -> grants.add(resource.grant)); views.keySet().forEach(scope -> grants.add("observe:" + scope));
		var scopes = new LinkedHashSet<>(views.keySet());
		for (var value : progressScopes) { String scope = OsJson.text(value.getAsJsonObject(), "scope"); if (!scopes.add(scope)) throw new IllegalArgumentException("duplicate_scope"); grants.add("observe:" + scope); }
		this.grants = Set.copyOf(grants);
		workerProfiles = map(input, "workerProfiles").deepCopy();
	}
	public JsonArray progressScopes() { return progressScopes.deepCopy(); }
	public JsonObject workerProfiles() { return workerProfiles.deepCopy(); }
	private static JsonObject map(JsonObject parent, String key) {
		var value = parent.has(key) ? OsJson.object(parent.get(key)) : new JsonObject();
		if (value.size() > 32 || value.keySet().stream().anyMatch(name -> name.isBlank() || name.length() > 128)) throw new IllegalArgumentException("configuration_limit"); return value;
	}
	static int integer(JsonObject value, String key, int fallback, int maximum) {
		long amount = OsJson.number(value, key, fallback); if (amount > maximum) throw new IllegalArgumentException("invalid_quantity"); return (int) amount;
	}
}
