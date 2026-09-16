package ai.moeru.airicraft.os;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** Trusted native projections shared by waits, workers and the resource scheduler. */
final class NativeViews {
	private final ObservationIndex observations;
	private final OsConfiguration configuration;
	private final LongSupplier millis;
	private final String epoch, session, world;
	private JsonObject response;
	private ResourceLedger.Observation inventory;
	private long captureSequence;
	private String progressClock, availabilityClock;
	private long progressThrough, availabilityThrough;
	record WorkerEvidence(String signature, String clock, Long ticks) {}
	NativeViews(OsConfiguration configuration, JsonObject first, LongSupplier millis) {
		this.configuration = configuration; this.millis = millis;
		epoch = OsJson.text(first, "epoch"); session = OsJson.text(first, "sessionId"); world = OsJson.text(first.getAsJsonObject("world"), "worldId"); observations = new ObservationIndex(epoch, millis);
	}
	void publish(JsonObject incoming, boolean quiescent, ResourceLedger ledger) {
		var frame = incoming.getAsJsonObject("frame");
		if (!epoch.equals(OsJson.text(frame, "epoch")) || !session.equals(OsJson.text(frame, "sessionId"))) throw new IllegalStateException("observation_epoch_changed");
		var worldState = frame.getAsJsonObject("world");
		if (!world.equals(OsJson.text(worldState, "worldId")) || !OsJson.bool(worldState, "alive", false) || OsJson.bool(worldState, "controllerBusy", true) || OsJson.bool(worldState, "reflexActive", true)) throw new IllegalStateException("world_not_ready");
		long capture = OsJson.number(frame, "captureSequence", 0);
		if (capture <= captureSequence) return;
		if (age(frame) >= 2000) { invalidate(); return; }
		Set<String> known = new HashSet<>(); configuration.resources.values().forEach(resource -> known.add(resource.key())); configuration.views.values().forEach(view -> known.addAll(view.resources().values()));
		var stocks = ContainerOperation.inventory(frame, known); var capacities = new LinkedHashMap<String, Integer>(); var targets = new HashSet<String>();
		for (var operation : configuration.operations.values()) {
			var seen = operation.observe(frame, known); merge(stocks, seen.stocks()); merge(capacities, seen.capacities()); targets.addAll(seen.targets());
		}
		inventory = new ResourceLedger.Observation(epoch, OsJson.text(frame, "captureId"), stocks, Map.of(), capacities, targets);
		if (quiescent) ledger.observe(inventory);
		for (var entry : configuration.views.entrySet()) {
			var view = entry.getValue(); var facts = new JsonArray();
			boolean available = view.location().equals("player") ? OsJson.bool(frame.getAsJsonObject("facts").getAsJsonObject("inventory"), "available", false) : targets.contains(view.location());
			for (var resource : view.resources().entrySet()) facts.add(stocks.containsKey(resource.getValue())
				? OsJson.obj("path", java.util.List.of("stock", resource.getKey()), "known", true, "value", stocks.get(resource.getValue()))
				: OsJson.obj("path", java.util.List.of("stock", resource.getKey()), "known", false, "reason", "location_unobserved"));
			observations.publish(entry.getKey(), OsJson.text(frame, "captureId"), capture, available, facts, "items:" + entry.getKey(), null, age(frame));
		}
		publishProgress(frame, capture);
		response = incoming.deepCopy(); captureSequence = capture;
	}
	private void publishProgress(JsonObject frame, long capture) {
		var specs = configuration.progressScopes(); if (specs.isEmpty()) return;
		var data = frame.getAsJsonObject("facts").getAsJsonObject("progress");
		if (data == null || !data.has("available") || !data.has("scopes")) throw new IllegalStateException("invalid_native_progress");
		boolean available = OsJson.bool(data, "available", false);
		var entries = data.getAsJsonArray("scopes");
		if (entries.size() != (available ? specs.size() : 0)) throw new IllegalStateException("invalid_native_progress");
		long through = 0;
		if (available) {
			if (!OsJson.text(data, "source").equals("native_completed_scope_ticks")) throw new IllegalStateException("invalid_native_progress");
			String clock = OsJson.text(data, "clockSession"); through = OsJson.number(data, "throughTick", -1);
			if (through < 0 || clock.equals(progressClock) && through < progressThrough) throw new IllegalStateException("progress_clock_regressed");
			progressClock = clock; progressThrough = through;
		}
		var indexed = new LinkedHashMap<String, JsonObject>();
		for (var value : entries) {
			var entry = value.getAsJsonObject();
			if (indexed.put(OsJson.text(entry, "scope"), entry) != null) throw new IllegalStateException("invalid_native_progress");
		}
		for (var value : specs) {
			var spec = value.getAsJsonObject(); String scope = OsJson.text(spec, "scope");
			var item = indexed.get(scope); Long ticks = null; String clock = "unavailable:" + scope;
			if (available) {
				if (item == null) throw new IllegalStateException("invalid_native_progress");
				OsJson.keys(item, Set.of("scope", "kind", "clockId", "eligibleTicks", "lastTickEligible"), Set.of("scope", "kind", "clockId", "eligibleTicks", "lastTickEligible"));
				if (!OsJson.text(item, "kind").equals(spec.has("chunks") ? "random_tick_chunks" : "passive_entities")) throw new IllegalStateException("invalid_native_progress");
				ticks = item.get("eligibleTicks").isJsonNull() ? null : OsJson.number(item, "eligibleTicks", -1);
				if (ticks != null && (ticks < 0 || ticks > through) || ticks == null && !item.get("lastTickEligible").isJsonNull()) throw new IllegalStateException("invalid_native_progress");
				if (ticks != null) OsJson.bool(item, "lastTickEligible", false);
				clock = "native-progress:" + progressClock + ":" + OsJson.text(item, "clockId");
			}
			var facts = new JsonArray();
			facts.add(ticks == null ? OsJson.obj("path", java.util.List.of("lastTickEligible"), "known", false) : OsJson.obj("path", java.util.List.of("lastTickEligible"), "known", true, "value", item.get("lastTickEligible")));
			observations.publish(scope, OsJson.text(frame, "captureId"), capture, ticks != null, facts, clock, ticks, age(frame));
		}
	}
	JsonObject grantedView(Set<String> grants) { return observations.view(scopes(grants)); }
	JsonObject observe(JsonObject query, Set<String> grants) {
		OsJson.keys(query, Set.of("scopes", "offset"), Set.of("scopes"));
		var requested = SkillDefinition.strings(query.get("scopes"), 32);
		if (requested.isEmpty() || !scopes(grants).containsAll(requested)) throw new IllegalArgumentException("observation_scope_not_granted");
		int offset = OsConfiguration.integer(query, "offset", 0, requested.size() - 1);
		String scope = query.getAsJsonArray("scopes").get(offset).getAsString(); var view = observations.view(Set.of(scope));
		view.add("nextOffset", OsJson.json(offset + 1 < requested.size() ? offset + 1 : null)); return view;
	}
	ObservationIndex.Wait waitFor(com.google.gson.JsonElement condition, JsonObject options, Set<String> grants) { return observations.waitFor(condition, options, scopes(grants)); }
	JsonObject poll(ObservationIndex.Wait wait) { return observations.poll(wait); }
	Set<String> scopes(Set<String> grants) { return grants.stream().filter(grant -> grant.startsWith("observe:")).map(grant -> grant.substring(8)).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
	JsonObject response() { return current() ? response.deepCopy() : null; }
	WorkerEvidence workerEvidence(Set<String> grants) {
		if (!current()) return null;
		var material = new JsonArray(); var clocks = new java.util.ArrayList<JsonObject>();
		for (var value : grantedView(grants).getAsJsonArray("scopes")) {
			var scope = value.getAsJsonObject(); var frame = scope.getAsJsonObject("frame");
			if (frame == null || !frame.get("captureId").equals(response.getAsJsonObject("frame").get("captureId"))) return null;
			material.add(OsJson.obj("scope", scope.get("scope"), "coverage", frame.get("coverage"), "facts", frame.get("facts")));
			var progress = frame.getAsJsonObject("progress");
			if (OsJson.bool(scope, "current", false) && !progress.get("eligibleTicks").isJsonNull()) clocks.add(progress);
		}
		return new WorkerEvidence(OsJson.digest(material), clocks.size() == 1 ? OsJson.text(clocks.getFirst(), "clockId") : null,
			clocks.size() == 1 ? OsJson.number(clocks.getFirst(), "eligibleTicks", 0) : null);
	}
	String epoch() { return epoch; }
	ResourceLedger.Observation inventory() { return inventory; }
	boolean current() { return response != null && age(response.getAsJsonObject("frame")) < 2000; }
	void invalidate() { response = null; }
	void gap() { response = null; observations.gap(); }
	double age(JsonObject frame) { return frame.get("captureAgeUpperBoundMillis").getAsDouble() + millis.getAsLong() - frame.get("receivedAtHostMillis").getAsLong(); }
	JsonObject availability() {
		if (!current()) return null;
		var frame = response.getAsJsonObject("frame"); var facts = frame.getAsJsonObject("facts"); var proof = facts.getAsJsonObject("availability");
		if (proof == null || proof.size() == 1 && !OsJson.bool(proof, "available", false)) return null;
		OsJson.keys(proof, Set.of("source", "available", "clockId", "gateRevision", "fromTick", "throughTick", "stamp", "contextId"), Set.of("source", "available", "clockId", "gateRevision", "fromTick", "throughTick", "stamp", "contextId"));
		String clock = OsJson.text(proof, "clockId"); long through = OsJson.number(proof, "throughTick", -1);
		if (!OsJson.text(proof, "source").equals("native_stable_material_ticks") || through < 0 || OsJson.number(proof, "gateRevision", 0) < 1 || availabilityClock != null && (!availabilityClock.equals(clock) || through < availabilityThrough)) throw new IllegalStateException("invalid_native_availability");
		availabilityClock = clock; availabilityThrough = through;
		if (!OsJson.bool(proof, "available", false)) {
			if (!proof.get("fromTick").isJsonNull() || !proof.get("stamp").isJsonNull()) throw new IllegalStateException("invalid_native_availability");
			return null;
		}
		var authority = response.getAsJsonObject("authority");
		long from = OsJson.number(proof, "fromTick", -1);
		if (from < 0 || from > through || authority.get("lease").isJsonNull() || !authority.get("active").isJsonNull() || !OsJson.bool(facts.getAsJsonObject("inventory"), "available", false)) throw new IllegalStateException("invalid_native_availability");
		if (authority.get("context").isJsonNull()) {
			if (!proof.get("contextId").isJsonNull()) throw new IllegalStateException("availability_context_mismatch");
		} else {
			var context = authority.getAsJsonObject("context"); var lease = authority.getAsJsonObject("lease"); var id = context.getAsJsonObject("id"); var evidence = context.getAsJsonObject("effects");
			if (!OsJson.text(context, "state").equals("RUNNING") || OsJson.bool(context, "released", true) || !OsJson.bool(evidence, "contextReady", false) ||
				!OsJson.text(evidence, "contextId").equals(OsJson.text(proof, "contextId")) || !id.get("epoch").equals(lease.get("epoch")) || !id.get("generation").equals(lease.get("generation"))) throw new IllegalStateException("availability_context_mismatch");
		}
		var args = OsJson.obj("world", frame.get("world"), "lease", authority.get("lease"), "gateRevision", proof.get("gateRevision"), "contextId", proof.get("contextId"), "window", facts.get("window"), "inventory", facts.get("inventory"));
		if (!NativeActionRuntime.fingerprint("eligibility-v2", "material", args).equals(OsJson.text(proof, "stamp"))) throw new IllegalStateException("availability_material_mismatch");
		return proof.deepCopy();
	}
	private static void merge(Map<String, Integer> target, Map<String, Integer> values) {
		for (var entry : values.entrySet()) { var previous = target.put(entry.getKey(), entry.getValue()); if (previous != null && !previous.equals(entry.getValue())) throw new IllegalStateException("observation_conflict"); }
	}
}
