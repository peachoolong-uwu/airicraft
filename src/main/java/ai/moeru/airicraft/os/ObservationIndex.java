package ai.moeru.airicraft.os;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** Scoped observations and level waits. Missing, stale and unloaded facts remain unknown. */
public final class ObservationIndex {
	private interface Predicate { String evaluate(); }
	private record Frame(String capture, long sequence, boolean available, JsonArray facts, Map<String, JsonObject> cells,
		String clock, Long eligible, long received, double age) {}
	private final Map<String, Frame> frames = new LinkedHashMap<>();
	private record Clock(String id, long through) {}
	private final Map<String, Clock> clocks = new LinkedHashMap<>();
	private final LongSupplier millis;
	private String epoch;
	private long sequence, gapSequence;
	public ObservationIndex(String epoch, LongSupplier millis) { this.epoch = epoch; this.millis = millis; }
	public void publish(String scope, String capture, long captureSequence, boolean available, JsonArray facts, String clock, Long eligible, double age) {
		if ((!frames.containsKey(scope) && frames.size() >= 32) || captureSequence < 1 || eligible != null && eligible < 0 || !Double.isFinite(age) || age < 0) throw new IllegalArgumentException("invalid_observation");
		var copied = OsJson.copy(facts, 12_288, 10, 1900).getAsJsonArray();
		if (copied.size() > 128) throw new IllegalArgumentException("observation_limit");
		var cells = new LinkedHashMap<String, JsonObject>();
		for (var value : copied) {
			var cell = OsJson.object(value); OsJson.keys(cell, Set.of("path", "known", "value", "reason"), Set.of("path", "known"));
			path(cell.get("path"));
			if (OsJson.bool(cell, "known", false) != cell.has("value") || cells.put(OsJson.canonical(cell.get("path")), cell) != null) throw new IllegalArgumentException("invalid_observation_cell");
		}
		var previous = frames.get(scope);
		if (previous != null) {
			if (captureSequence < previous.sequence) return;
			if (captureSequence == previous.sequence) {
				if (!previous.capture.equals(capture) || !previous.facts.equals(copied) || previous.available != available || !java.util.Objects.equals(previous.clock, clock) || !java.util.Objects.equals(previous.eligible, eligible)) throw new IllegalArgumentException("capture_conflict");
				return;
			}
		}
		var highWater = clocks.get(scope);
		if (eligible != null) {
			if (highWater != null && highWater.id.equals(clock) && eligible < highWater.through) throw new IllegalArgumentException("progress_clock_regressed");
			if (highWater == null && clocks.size() >= 32) throw new IllegalArgumentException("scope_capacity");
			clocks.put(scope, new Clock(clock, eligible));
		}
		frames.put(scope, new Frame(capture, captureSequence, available, copied, Map.copyOf(cells), clock, eligible, millis.getAsLong(), age)); sequence++;
	}
	public void gap() { frames.clear(); gapSequence = ++sequence; }
	public void epoch(String next) { if (!epoch.equals(next)) { epoch = next; clocks.clear(); gap(); } }
	public JsonObject view(Set<String> scopes) {
		var pages = new JsonArray();
		for (String scope : scopes.stream().sorted().toList()) {
			var frame = frames.get(scope);
			pages.add(OsJson.obj("scope", scope, "current", fresh(frame), "frame", frame == null ? null : OsJson.obj("epoch", epoch, "scope", scope,
				"captureId", frame.capture, "captureSequence", frame.sequence, "coverage", OsJson.obj("available", frame.available, "complete", frame.available, "truncated", false),
				"facts", frame.facts, "progress", OsJson.obj("clockId", frame.clock, "eligibleTicks", frame.eligible))));
		}
		return OsJson.object(OsJson.copy(OsJson.obj("epoch", epoch, "sequence", sequence, "scopes", pages), 12_288, 16, 1900));
	}
	public JsonObject cell(String scope, JsonElement address) {
		var frame = frames.get(scope); var cell = frame == null ? null : frame.cells.get(OsJson.canonical(address));
		return fresh(frame) && cell != null && OsJson.bool(cell, "known", false) ? OsJson.obj("known", true, "value", cell.get("value")) : OsJson.obj("known", false);
	}
	public Wait waitFor(JsonElement condition, JsonObject options, Set<String> allowedScopes) {
		OsJson.keys(options, Set.of("cursor", "deadline"), Set.of());
		var scopes = new HashSet<String>(); var predicate = compile(OsJson.copy(condition), 0, new int[] {64}, scopes);
		var deadline = options.has("deadline") && !options.get("deadline").isJsonNull() ? OsJson.object(options.get("deadline")) : null;
		String clock = deadline == null ? null : OsJson.text(deadline, "clock");
		if (deadline != null) {
			if (clock.equals("wall")) { OsJson.keys(deadline, Set.of("clock", "milliseconds"), Set.of("clock", "milliseconds")); if (OsJson.number(deadline, "milliseconds", 0) == 0) throw new IllegalArgumentException("invalid_deadline"); }
			else if (clock.equals("eligible_ticks")) { OsJson.keys(deadline, Set.of("clock", "scope", "ticks"), Set.of("clock", "scope", "ticks")); scopes.add(OsJson.text(deadline, "scope")); if (OsJson.number(deadline, "ticks", 0) == 0) throw new IllegalArgumentException("invalid_deadline"); }
			else throw new IllegalArgumentException("invalid_deadline_clock");
		}
		if (!allowedScopes.containsAll(scopes)) throw new IllegalArgumentException("observation_scope_not_granted");
		var cursor = options.has("cursor") ? OsJson.object(options.get("cursor")) : OsJson.obj("epoch", epoch, "sequence", sequence);
		OsJson.keys(cursor, Set.of("epoch", "sequence"), Set.of("epoch", "sequence"));
		long position = OsJson.number(cursor, "sequence", 0); String cursorEpoch = OsJson.text(cursor, "epoch");
		if (cursorEpoch.equals(epoch) && position > sequence) throw new IllegalArgumentException("invalid_wait_cursor");
		var wait = new Wait(predicate, epoch, scopes, deadline, millis.getAsLong(), sequence);
		if (!cursorEpoch.equals(epoch)) wait.outcome = OsJson.obj("status", "epoch_changed");
		else if (position < Math.max(gapSequence, sequence - 512)) wait.outcome = OsJson.obj("status", "gap");
		return wait;
	}
	public JsonObject poll(Wait wait) {
		if (!wait.epoch.equals(epoch)) return OsJson.obj("status", "epoch_changed");
		if (wait.outcome != null) return wait.outcome.deepCopy();
		if (wait.cursor < gapSequence) return wait.outcome = OsJson.obj("status", "gap");
		boolean deadline = false;
		if (wait.deadline != null) {
			if (OsJson.text(wait.deadline, "clock").equals("wall")) deadline = millis.getAsLong() - wait.started >= OsJson.number(wait.deadline, "milliseconds", 0);
			else {
				var frame = frames.get(OsJson.text(wait.deadline, "scope"));
				if (fresh(frame) && frame.eligible != null) {
					if (wait.clock != null && wait.clock.equals(frame.clock) && wait.previous != null) wait.elapsed += Math.max(0, frame.eligible - wait.previous);
					wait.clock = frame.clock; wait.previous = frame.eligible;
				} else { wait.clock = null; wait.previous = null; }
				deadline = wait.elapsed >= OsJson.number(wait.deadline, "ticks", 0);
			}
		}
		String truth = wait.predicate.evaluate();
		if (truth.equals("met") || deadline) wait.outcome = OsJson.obj("status", truth.equals("met") ? "met" : "deadline", "epoch", epoch, "cursor", OsJson.obj("epoch", epoch, "sequence", sequence));
		return wait.outcome == null ? OsJson.obj("status", "pending") : wait.outcome.deepCopy();
	}
	private Predicate compile(JsonElement value, int depth, int[] budget, Set<String> scopes) {
		if (depth > 8 || --budget[0] < 0) throw new IllegalArgumentException("condition_limit");
		var condition = OsJson.object(value);
		for (String group : List.of("all", "any")) if (condition.has(group)) {
			OsJson.keys(condition, Set.of(group), Set.of(group)); var children = condition.getAsJsonArray(group);
			if (children.isEmpty() || children.size() > 64) throw new IllegalArgumentException("invalid_condition");
			var predicates = new ArrayList<Predicate>(); for (var child : children) predicates.add(compile(child, depth + 1, budget, scopes));
			return () -> {
				boolean unknown = false;
				for (var predicate : predicates) { String result = predicate.evaluate(); if (group.equals("all") && result.equals("unmet") || group.equals("any") && result.equals("met")) return result; unknown |= result.equals("unknown"); }
				return unknown ? "unknown" : group.equals("all") ? "met" : "unmet";
			};
		}
		if (condition.has("not")) {
			OsJson.keys(condition, Set.of("not"), Set.of("not")); var predicate = compile(condition.get("not"), depth + 1, budget, scopes);
			return () -> switch (predicate.evaluate()) { case "met" -> "unmet"; case "unmet" -> "met"; default -> "unknown"; };
		}
		String operation = List.of("equals", "atLeast", "known").stream().filter(condition::has).findFirst().orElseThrow(() -> new IllegalArgumentException("invalid_condition"));
		OsJson.keys(condition, Set.of("scope", "path", operation), Set.of("scope", "path", operation));
		String scope = OsJson.text(condition, "scope"); scopes.add(scope); path(condition.get("path"));
		if (operation.equals("known") && !OsJson.bool(condition, "known", false)) throw new IllegalArgumentException("invalid_condition");
		if (operation.equals("atLeast") && (!condition.get("atLeast").isJsonPrimitive() || !condition.getAsJsonPrimitive("atLeast").isNumber())) throw new IllegalArgumentException("invalid_condition");
		return () -> {
			var cell = cell(scope, condition.get("path")); boolean known = OsJson.bool(cell, "known", false);
			if (operation.equals("known")) return known ? "met" : "unmet";
			if (!known) return "unknown";
			var actual = cell.get("value");
			boolean matches = operation.equals("equals") ? actual.equals(condition.get("equals")) : actual.isJsonPrimitive() && actual.getAsJsonPrimitive().isNumber() && actual.getAsDouble() >= condition.get("atLeast").getAsDouble();
			return matches ? "met" : "unmet";
		};
	}
	private boolean fresh(Frame frame) { return frame != null && frame.available && millis.getAsLong() >= frame.received && frame.age + millis.getAsLong() - frame.received < 2000; }
	private static void path(JsonElement value) {
		if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > 8) throw new IllegalArgumentException("invalid_observation_path");
		for (var key : value.getAsJsonArray()) if (!key.isJsonPrimitive() || !(key.getAsJsonPrimitive().isString() && key.getAsString().length() <= 128 || key.getAsJsonPrimitive().isNumber() && key.getAsDouble() == key.getAsInt() && key.getAsInt() >= 0 && key.getAsInt() < 2048)) throw new IllegalArgumentException("invalid_observation_path");
	}
	public static final class Wait {
		private final Predicate predicate; private final String epoch; private final Set<String> scopes; private final JsonObject deadline; private final long started, cursor;
		private JsonObject outcome; private String clock; private Long previous; private long elapsed;
		private Wait(Predicate predicate, String epoch, Set<String> scopes, JsonObject deadline, long started, long cursor) {
			this.predicate = predicate; this.epoch = epoch; this.scopes = Set.copyOf(scopes); this.deadline = deadline == null ? null : deadline.deepCopy(); this.started = started; this.cursor = cursor;
		}
	}
}
