package ai.moeru.airicraft.os;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Completed native scope updates, never elapsed-time estimates. Registration and reads may cross threads. */
public final class NativeProgressClocks {
	private static final long MAXIMUM_TICKS = 9_007_199_254_740_991L;
	private String session = UUID.randomUUID().toString();
	private final Map<String, Entry> entries = new LinkedHashMap<>();
	private final Map<Source, List<Entry>> bySource = new HashMap<>();
	private long throughTick;
	private boolean ticking;

	public synchronized void configure(List<Scope> scopes) {
		validateScopes(scopes);
		var next = new LinkedHashMap<String, Entry>();
		for (var scope : scopes) {
			Entry previous = entries.get(scope.scope());
			next.put(scope.scope(), previous != null && previous.scope.equals(scope) ? previous : new Entry(scope));
		}
		entries.clear(); entries.putAll(next); bySource.clear();
		for (var entry : entries.values()) for (var source : entry.scope.sources())
			bySource.computeIfAbsent(source, ignored -> new ArrayList<>()).add(entry);
	}

	public synchronized void begin() {
		if (ticking) {
			// An unfinished tick is a continuity break; no partial update becomes historical progress.
			session = UUID.randomUUID().toString(); throughTick = 0;
			for (var entry : entries.values()) entry.reset();
		}
		ticking = true;
		for (var entry : entries.values()) { entry.active = true; entry.seen.clear(); }
	}
	public synchronized void chunk(String dimension, int x, int z) {
		mark(new Source(dimension, Kind.RANDOM_TICK_CHUNKS, x, z, null));
	}
	public synchronized void entity(String dimension, UUID entityId) {
		mark(new Source(dimension, Kind.PASSIVE_ENTITIES, 0, 0, entityId));
	}
	private void mark(Source source) {
		if (!ticking) return;
		for (var entry : bySource.getOrDefault(source, List.of())) if (entry.active) entry.seen.add(source);
	}
	public synchronized void complete(boolean advancing) {
		if (!ticking) return;
		if (advancing && throughTick == MAXIMUM_TICKS) throw new NativeActionRuntime.Rejected("progress_clock_exhausted");
		if (advancing) throughTick++;
		for (var entry : entries.values()) if (entry.active) {
			entry.known = true;
			entry.lastTickEligible = advancing && entry.seen.size() == entry.scope.sources().size();
			if (entry.lastTickEligible) entry.eligibleTicks++;
		}
		ticking = false;
	}
	public synchronized Snapshot snapshot() {
		return new Snapshot(session, throughTick, entries.values().stream().map(entry -> new Progress(entry.scope.scope(),
			entry.scope.sources().getFirst().kind().name().toLowerCase(Locale.ROOT), entry.clockId,
			entry.known ? entry.eligibleTicks : null, entry.known ? entry.lastTickEligible : null)).toList());
	}
	public synchronized boolean empty() { return entries.isEmpty(); }

	public enum Kind { RANDOM_TICK_CHUNKS, PASSIVE_ENTITIES }
	public record Source(String dimension, Kind kind, int x, int z, UUID entityId) {
		public Source {
			if (!text(dimension, 256) || kind == null || Math.abs((long) x) > 1_875_000 || Math.abs((long) z) > 1_875_000
				|| (kind == Kind.RANDOM_TICK_CHUNKS ? entityId != null : entityId == null || x != 0 || z != 0)) throw invalid();
		}
	}
	public record Scope(String scope, List<Source> sources) {
		public Scope {
			if (!text(scope, 128) || sources == null || sources.isEmpty() || sources.size() > 16
				|| sources.stream().anyMatch(source -> source == null) || new HashSet<>(sources).size() != sources.size()
				|| sources.stream().map(Source::kind).distinct().count() != 1
				|| sources.stream().map(Source::dimension).distinct().count() != 1) throw invalid();
			sources = sources.stream().sorted(Comparator.comparing(Source::toString)).toList();
		}
	}
	public record Progress(String scope, String kind, String clockId, Long eligibleTicks, Boolean lastTickEligible) {}
	public record Snapshot(String clockSession, long throughTick, List<Progress> scopes) {}

	public static List<Scope> parse(JsonArray values, String dimension) {
		if (values == null || values.size() > 32) throw invalid();
		var scopes = new ArrayList<Scope>();
		for (var value : values) {
			if (!value.isJsonObject()) throw invalid();
			var object = value.getAsJsonObject();
			boolean chunks = object.has("chunks");
			String field = chunks ? "chunks" : "entities";
			if (!object.keySet().equals(Set.of("scope", field)) || !object.get(field).isJsonArray()) throw invalid();
			var sources = new ArrayList<Source>();
			var array = object.getAsJsonArray(field);
			if (array.isEmpty() || array.size() > 16) throw invalid();
			for (var target : array) {
				if (chunks) {
					if (!target.isJsonObject() || !target.getAsJsonObject().keySet().equals(Set.of("x", "z"))) throw invalid();
					var point = target.getAsJsonObject();
					sources.add(new Source(dimension, Kind.RANDOM_TICK_CHUNKS, coordinate(point.get("x")), coordinate(point.get("z")), null));
				} else {
					String id = string(target, 36);
					try {
						UUID uuid = UUID.fromString(id);
						if (!uuid.toString().equals(id.toLowerCase(Locale.ROOT))) throw invalid();
						sources.add(new Source(dimension, Kind.PASSIVE_ENTITIES, 0, 0, uuid));
					} catch (IllegalArgumentException exception) { throw invalid(); }
				}
			}
			scopes.add(new Scope(string(object.get("scope"), 128), sources));
		}
		validateScopes(scopes);
		return List.copyOf(scopes);
	}
	private static void validateScopes(List<Scope> scopes) {
		if (scopes == null || scopes.size() > 32 || scopes.stream().anyMatch(scope -> scope == null)
			|| scopes.stream().map(Scope::scope).distinct().count() != scopes.size()
			|| scopes.stream().mapToInt(scope -> scope.sources().size()).sum() > 128) throw invalid();
	}
	private static int coordinate(JsonElement value) {
		try {
			if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid();
			return value.getAsBigDecimal().intValueExact();
		} catch (ArithmeticException exception) { throw invalid(); }
	}
	private static String string(JsonElement value, int maximum) {
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !text(value.getAsString(), maximum)) throw invalid();
		return value.getAsString();
	}
	private static boolean text(String value, int maximum) { return value != null && !value.isBlank() && value.length() <= maximum; }
	private static NativeActionRuntime.Rejected invalid() { return new NativeActionRuntime.Rejected("invalid_progress_scopes"); }

	private static final class Entry {
		private final Scope scope;
		private final Set<Source> seen = new HashSet<>();
		private String clockId;
		private long eligibleTicks;
		private boolean active;
		private boolean known;
		private boolean lastTickEligible;
		private Entry(Scope scope) { this.scope = scope; reset(); }
		private void reset() { clockId = UUID.randomUUID().toString(); eligibleTicks = 0; known = false; lastTickEligible = false; active = false; seen.clear(); }
	}
}
