package ai.moeru.airicraft.agent.reflex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bounded, deterministic route scoring. Coordinates are feet cells; time and speed use game ticks. */
public final class CombatPositioning {
	static final double HORIZON_TICKS = 24;
	static final int BEAM_WIDTH = 24;
	static final int MAX_STEPS = 8;
	private CombatPositioning() { }

	public record Cell(int x, int y, int z) { }
	public record Edge(Cell destination, double ticks) {
		public Edge {
			if (!Double.isFinite(ticks) || ticks <= 0) throw new IllegalArgumentException("positive finite travel time required");
		}
	}
	public record Threat(double x, double y, double z, double blocksPerTick, double reach) {
		public Threat {
			if (!Double.isFinite(x + y + z + blocksPerTick + reach) || blocksPerTick < 0 || reach < 0)
				throw new IllegalArgumentException("finite position, nonnegative speed and reach required");
		}
	}
	public record Decision(List<Cell> route, double risk, double standingRisk, int expandedRoutes) {
		public Decision { route = List.copyOf(route); }
		public Cell nextStep() { return route.size() > 1 ? route.get(1) : null; }
	}
	public record Steering(boolean forward, boolean back, boolean left, boolean right) { }

	/** Map a world-space waypoint to keys while the camera continues facing the threat group. */
	public static Steering steering(double x, double z, double targetX, double targetZ, double facingX, double facingZ) {
		double dx = targetX - x, dz = targetZ - z, fx = facingX - x, fz = facingZ - z;
		double length = Math.hypot(dx, dz) * Math.hypot(fx, fz);
		if (length < .001) return new Steering(false, false, false, false);
		double forward = (dx * fx + dz * fz) / length;
		double left = (dx * fz - dz * fx) / length;
		double threshold = Math.sin(Math.PI / 8);
		return new Steering(forward > threshold, forward < -threshold, left > threshold, left < -threshold);
	}
	private record Route(List<Cell> cells, double ticks, double exposure, List<Threat> pursuers, double score) {
		Cell end() { return cells.getLast(); }
	}
	private record Arrival(Cell cell, Cell firstStep, int timeBucket) { }
	private record Pursuit(List<Threat> threats, double exposure) { }

	/** The graph contains only observed, traversable movement edges, never disconnected standing spots. */
	public static Decision choose(Cell origin, Map<Cell, List<Edge>> graph, List<Threat> threats, Cell previousStep) {
		Route standing = scored(List.of(origin), 0, 0, threats, graph, threats, previousStep);
		Route best = standing;
		List<Route> beam = List.of(standing);
		int expanded = 0;
		for (int depth = 0; depth < MAX_STEPS && !beam.isEmpty(); depth++) {
			Map<Arrival, Route> arrivals = new HashMap<>();
			for (Route route : beam) {
				for (Edge edge : graph.getOrDefault(route.end(), List.of())) {
					if (route.cells().contains(edge.destination()) || route.ticks() + edge.ticks() > HORIZON_TICKS) continue;
					expanded++;
					var cells = new ArrayList<>(route.cells());
					cells.add(edge.destination());
					Pursuit pursuit = pursue(route.end(), edge.destination(), edge.ticks(), route.pursuers());
					Route next = scored(List.copyOf(cells), route.ticks() + edge.ticks(), route.exposure() + pursuit.exposure(),
						pursuit.threats(), graph, threats, previousStep);
					Arrival key = new Arrival(next.end(), cells.get(1), (int) (next.ticks() / 3));
					Route existing = arrivals.get(key);
					if (existing == null || ORDER.compare(next, existing) < 0) arrivals.put(key, next);
					if (ORDER.compare(next, best) < 0) best = next;
				}
			}
			beam = arrivals.values().stream().sorted(ORDER).limit(BEAM_WIDTH).toList();
		}
		return new Decision(best.cells(), best.score(), standing.score(), expanded);
	}

	private static final Comparator<Route> ORDER = Comparator.comparingDouble(Route::score)
		.thenComparingDouble(Route::ticks)
		.thenComparing(route -> route.cells().toString());

	private static Route scored(List<Cell> cells, double ticks, double exposure, List<Threat> pursuers,
		Map<Cell, List<Edge>> graph, List<Threat> threats, Cell previousStep) {
		Cell end = cells.getLast();
		// Compare every route, including standing still, over the same time horizon.
		Pursuit rest = pursue(end, end, HORIZON_TICKS - ticks, pursuers);
		double score = (exposure + rest.exposure()) / HORIZON_TICKS + risk(end.x() + .5, end.z() + .5, 0, rest.threats()) * .3;
		if (threats.size() > 1 && graph.getOrDefault(end, List.of()).size() <= 1) score += 5;
		if (previousStep != null && cells.size() > 1 && !cells.get(1).equals(previousStep)) score += .5;
		if (threats.size() > 1 && cells.size() > 1) score += orbitPenalty(cells.getFirst(), end, threats);
		return new Route(cells, ticks, exposure, pursuers, score);
	}

	private static Pursuit pursue(Cell from, Cell to, double duration, List<Threat> threats) {
		double total = 0;
		// Sample the route interior too: a safe endpoint does not justify running through a mob.
		int samples = Math.max(1, (int) Math.ceil(duration / 2));
		for (int sample = 1; sample <= samples; sample++) {
			double fraction = (double) sample / samples;
			double x = from.x() + .5 + (to.x() - from.x()) * fraction;
			double z = from.z() + .5 + (to.z() - from.z()) * fraction;
			var advanced = new ArrayList<Threat>(threats.size());
			for (Threat threat : threats) {
				double dx = x - threat.x(), dz = z - threat.z(), distance = Math.hypot(dx, dz);
				double move = Math.min(Math.max(0, distance - threat.reach() * .75), threat.blocksPerTick() * duration / samples);
				double ratio = distance < .001 ? 0 : move / distance;
				advanced.add(new Threat(threat.x() + dx * ratio, threat.y(), threat.z() + dz * ratio, threat.blocksPerTick(), threat.reach()));
			}
			threats = advanced;
			total += risk(x, z, 0, threats);
		}
		return new Pursuit(threats, total * duration / samples);
	}

	private static double orbitPenalty(Cell origin, Cell end, List<Threat> threats) {
		double cx = 0, cz = 0, nearest = Double.POSITIVE_INFINITY;
		for (Threat threat : threats) {
			cx += threat.x(); cz += threat.z();
			nearest = Math.min(nearest, Math.hypot(threat.x() - origin.x() - .5, threat.z() - origin.z() - .5));
		}
		// Escape contact first. Once spaced out, prefer a consistent tangent around the pack
		// over backing indefinitely away or alternating left/right and splitting pursuers.
		if (nearest < 3.5) return 0;
		double rx = origin.x() + .5 - cx / threats.size(), rz = origin.z() + .5 - cz / threats.size();
		double dx = end.x() - origin.x(), dz = end.z() - origin.z();
		double length = Math.hypot(rx, rz) * Math.hypot(dx, dz);
		if (length < .001) return 0;
		double radial = Math.abs(rx * dx + rz * dz) / length;
		double reverseOrbit = Math.max(0, -(rx * dz - rz * dx) / length);
		return radial * 2 + reverseOrbit * 2;
	}

	static double risk(double x, double z, double ticks, List<Threat> threats) {
		double pressure = 0, pincer = 0;
		for (int i = 0; i < threats.size(); i++) {
			Threat threat = threats.get(i);
			double dx = threat.x() - x, dz = threat.z() - z;
			double distance = Math.hypot(dx, dz);
			// Horizontal pressure is conservative across elevation; no claim of an exact mob AI route.
			double clearance = distance - threat.reach() - threat.blocksPerTick() * ticks;
			double urgency = Math.clamp((8 - clearance) / 8, 0, 1);
			pressure += 1 / Math.pow(1 + Math.max(0, clearance), 2) + Math.min(8, Math.max(0, -clearance)) * 1.5;
			// Do not treat passing through an entity's body as a cheap way out of a pincer.
			pressure += Math.pow(Math.max(0, 1.2 - distance), 2) * 30;
			for (int j = 0; j < i; j++) {
				Threat other = threats.get(j);
				double ox = other.x() - x, oz = other.z() - z, od = Math.hypot(ox, oz);
				double opposite = distance < .01 || od < .01 ? 1 : Math.max(0, -(dx * ox + dz * oz) / (distance * od));
				double otherUrgency = Math.clamp((8 - od + other.reach() + other.blocksPerTick() * ticks) / 8, 0, 1);
				pincer += opposite * Math.min(urgency, otherUrgency);
			}
		}
		return pressure * 8 + pincer * 18;
	}
}
