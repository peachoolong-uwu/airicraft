package ai.moeru.airicraft.os;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Declarative scheduling policy. Selection grants no physical authority. */
public final class WorkScheduler {
	public static final int CONTEXT_OPERATIONS = 8, CONTEXT_TICKS = 1200, OVERDUE_TICKS = 2400, FISHING_YIELD_TICKS = 100;
	public record Travel(int bonus, String source, boolean uncertain) {
		public Travel { if (bonus < 0 || bonus > 3 || !Set.of("observed", "configured", "unknown").contains(source) || source.equals("unknown") && (bonus != 0 || !uncertain)) throw new IllegalArgumentException("invalid_travel"); }
	}
	public record Offer(String id, Set<String> roots, String kind, int priority, String context, boolean ready, Travel travel) {
		public Offer(String id, Set<String> roots, String kind, int priority, String context, boolean ready) { this(id, roots, kind, priority, context, ready, new Travel(0, "unknown", true)); }
		public Offer {
			roots = Set.copyOf(roots);
			if (roots.isEmpty() || roots.size() > 12 || priority < 0 || !Set.of("land", "fishing").contains(kind)) throw new IllegalArgumentException("invalid_offer");
		}
	}
	public record Context(String id, String phase, int operations, long elapsedTicks) {}
	public record Decision(String kind, String offerId, String reason) {}
	public record Activity(String id, String kind, String phase, boolean biteReady) {
		public Activity { if (id == null || id.isBlank() || !Set.of("land", "fishing").contains(kind) || !Set.of("preparing", "acting", "waiting", "releasing").contains(phase)) throw new IllegalArgumentException("invalid_scheduler_activity"); }
	}
	private record Entry(Offer offer, long order) {}
	private static final class Age { long ticks; Long overdue; long order; }
	private final Map<String, Age> roots = new LinkedHashMap<>();
	private Map<String, Entry> offers = new LinkedHashMap<>();
	private long order, tick;
	private String outside;
	private String yieldingActivity;
	private long fishingDeadline;
	private boolean clockKnown;
	public void update(List<Offer> values) { update(values.stream().flatMap(offer -> offer.roots.stream()).collect(Collectors.toSet()), values); }
	public void update(Set<String> activeRoots, List<Offer> values) {
		if (activeRoots.size() > 12 || values.size() > 1024) throw new IllegalArgumentException("schedule_capacity");
		var next = new LinkedHashMap<String, Entry>();
		for (var offer : values) {
			if (!activeRoots.containsAll(offer.roots) || next.containsKey(offer.id)) throw new IllegalArgumentException("invalid_schedule");
			var previous = offers.get(offer.id);
			if (previous != null && (!previous.offer.roots.equals(offer.roots) || !previous.offer.kind.equals(offer.kind) || !Objects.equals(previous.offer.context, offer.context))) throw new IllegalArgumentException("offer_identity_conflict");
			next.put(offer.id, new Entry(offer, previous == null ? ++order : previous.order));
		}
		roots.keySet().retainAll(activeRoots); activeRoots.forEach(id -> roots.computeIfAbsent(id, ignored -> new Age())); offers = next;
	}
	public void advance(long from, long to, Set<String> eligible, boolean covered) {
		if (from < tick || to < from || !roots.keySet().containsAll(eligible)) throw new IllegalArgumentException("invalid_scheduler_progress");
		var candidates = candidates();
		if (covered) for (String id : eligible) {
			var age = roots.get(id);
			long arrival = candidates.stream().filter(entry -> entry.offer.roots.contains(id)).mapToLong(Entry::order).min().orElseThrow(() -> new IllegalArgumentException("ineligible_scheduler_root"));
			long before = age.ticks; age.ticks += Math.min(OVERDUE_TICKS - age.ticks, to - from);
			if (age.overdue == null && age.ticks == OVERDUE_TICKS) { age.overdue = from + OVERDUE_TICKS - before; age.order = arrival; }
		}
		tick = to; clockKnown = true;
	}
	public Decision choose(boolean available, Context context) {
		return choose(available, context, null);
	}
	public Decision choose(boolean available, Context context, Activity activity) {
		if (activity != null) return activeDecision(activity);
		yieldingActivity = null;
		if (!available) return new Decision("wait", null, "authority_unavailable");
		if (context != null && !context.phase.equals("ready")) return new Decision("wait", null, "context_not_ready");
		var candidates = candidates();
		if (outside != null && candidates.stream().noneMatch(entry -> !Objects.equals(entry.offer.context, outside))) outside = null;
		if (context != null && (context.operations >= CONTEXT_OPERATIONS || context.elapsedTicks >= CONTEXT_TICKS)) {
			if (candidates.stream().anyMatch(entry -> !Objects.equals(entry.offer.context, context.id))) outside = context.id;
			return new Decision("close_context", null, "context_budget");
		}
		if (candidates.isEmpty()) return new Decision(context == null ? "wait" : "close_context", null, "no_feasible_work");
		String overdue = roots.entrySet().stream().filter(entry -> entry.getValue().overdue != null && candidates.stream().anyMatch(offer -> offer.offer.roots.contains(entry.getKey())))
			.min(Comparator.<Map.Entry<String, Age>>comparingLong(entry -> entry.getValue().overdue).thenComparingLong(entry -> entry.getValue().order)).map(Map.Entry::getKey).orElse(null);
		String reason = overdue != null ? "overdue" : outside != null ? "outside_turn" : "score";
		var winner = candidates.stream().filter(entry -> overdue != null ? entry.offer.roots.contains(overdue) : outside == null || !Objects.equals(entry.offer.context, outside))
			.min(Comparator.<Entry>comparingLong(entry -> -score(entry.offer, context)).thenComparingLong(Entry::order)).orElseThrow();
		if (context != null && !context.id.equals(winner.offer.context)) return new Decision("close_context", winner.offer.id, "incompatible_work");
		return new Decision("select", winner.offer.id, reason);
	}
	private Decision activeDecision(Activity activity) {
		if (!activity.id.equals(yieldingActivity)) yieldingActivity = null;
		if (!activity.kind.equals("fishing") || activity.phase.equals("releasing")) return new Decision("wait", activity.id, "activity_in_progress");
		if (yieldingActivity == null && candidates().stream().noneMatch(entry -> entry.offer.kind.equals("land"))) return new Decision("wait", activity.id, "activity_in_progress");
		if (yieldingActivity == null) { yieldingActivity = activity.id; fishingDeadline = !clockKnown || tick > Long.MAX_VALUE - FISHING_YIELD_TICKS ? tick : tick + FISHING_YIELD_TICKS; }
		String action = activity.phase.equals("preparing") ? "yield_before_cast" : activity.biteReady ? "retrieve_bite" : tick >= fishingDeadline ? "reel_in" : "wait_for_bite";
		return new Decision("yield_fishing", activity.id, action);
	}
	public void served(Offer selected) {
		for (String root : selected.roots) if (roots.containsKey(root)) roots.put(root, new Age());
		if (outside != null && !outside.equals(selected.context)) outside = null;
	}
	public Map<String, Long> ages() { var values = new LinkedHashMap<String, Long>(); roots.forEach((id, age) -> values.put(id, age.ticks)); return Map.copyOf(values); }
	private List<Entry> candidates() {
		var ready = offers.values().stream().filter(entry -> entry.offer.ready).toList();
		var land = ready.stream().filter(entry -> entry.offer.kind.equals("land")).toList(); return land.isEmpty() ? ready : land;
	}
	private long score(Offer offer, Context context) {
		return (long) offer.priority + offer.roots.stream().mapToLong(id -> roots.get(id).ticks).max().orElse(0) / 300 + (context != null && context.id.equals(offer.context) ? 3 : 0) + offer.travel.bonus;
	}
}
