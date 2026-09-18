package ai.moeru.airicraft.agent.reflex;

import ai.moeru.airicraft.agent.spatial.WorldTravelPolicy;
import baritone.api.BaritoneAPI;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.Moves;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Client-thread terrain observation; no terrain edits, global settings changes, or pathfinding effects. */
final class MinecraftCombatPositioning {
	static final int MAX_CELLS = 128;
	static final int RADIUS = 6;
	private static final int REPLAN_TICKS = 6;
	private static final List<Moves> MOVES = List.of(
		Moves.TRAVERSE_NORTH, Moves.TRAVERSE_SOUTH, Moves.TRAVERSE_EAST, Moves.TRAVERSE_WEST,
		Moves.ASCEND_NORTH, Moves.ASCEND_SOUTH, Moves.ASCEND_EAST, Moves.ASCEND_WEST,
		Moves.DESCEND_NORTH, Moves.DESCEND_SOUTH, Moves.DESCEND_EAST, Moves.DESCEND_WEST);
	private record Observation(Vec3d position, long tick) { }
	private final Map<String, Observation> previous = new HashMap<>();
	private long plannedTick = Long.MIN_VALUE;
	private CombatPositioning.Decision decision;
	private int terrainCells;
	private long planningNanos;

	CombatPositioning.Decision plan(MinecraftClient client, List<LivingEntity> entities, long tick, boolean shielding) {
		var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
		BlockPos feet = baritone.getPlayerContext().playerFeet();
		CombatPositioning.Cell origin = cell(feet);
		if (decision != null && tick - plannedTick < REPLAN_TICKS
			&& !origin.equals(decision.nextStep())) return decision;
		long started = System.nanoTime();
		var context = new CalculationContext(baritone);
		List<CombatPositioning.Threat> threats = new ArrayList<>();
		for (LivingEntity entity : entities) {
			var attribute = entity.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED);
			double speed = Math.max(entity.getVelocity().horizontalLength(), attribute == null ? .1 : attribute.getValue() * 1.3);
			Observation old = previous.put(entity.getUuidAsString(), new Observation(entity.getPos(), tick));
			if (old != null && tick > old.tick()) speed = Math.max(speed,
				entity.getPos().subtract(old.position()).horizontalLength() / (tick - old.tick()));
			threats.add(new CombatPositioning.Threat(entity.getX(), entity.getY(), entity.getZ(), speed,
				2.4 + Math.max(0, (entity.getWidth() - .6) / 2)));
		}
		previous.keySet().retainAll(entities.stream().map(LivingEntity::getUuidAsString).toList());
		Map<CombatPositioning.Cell, List<CombatPositioning.Edge>> graph = terrain(context, origin, shielding ? 5 : 1);
		terrainCells = graph.size();
		decision = CombatPositioning.choose(origin, graph, threats, decision == null ? null : decision.nextStep());
		plannedTick = tick;
		planningNanos = System.nanoTime() - started;
		return decision;
	}

	Map<String, Object> evidence() {
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("decision", decision);
		evidence.put("terrainCells", terrainCells);
		evidence.put("planningMicros", planningNanos / 1000);
		evidence.put("plannedTick", plannedTick);
		evidence.put("threatCount", previous.size());
		return evidence;
	}

	/** Fresh validation prevents a cached escape hop surviving a block change or knockback. */
	boolean canStepTo(CombatPositioning.Cell target) {
		var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
		var context = new CalculationContext(baritone);
		var origin = cell(baritone.getPlayerContext().playerFeet());
		if (origin.equals(target)) return true;
		return edges(context, origin, 1).stream().anyMatch(edge -> edge.destination().equals(target));
	}

	void invalidate() { plannedTick = Long.MIN_VALUE; decision = null; }

	private static Map<CombatPositioning.Cell, List<CombatPositioning.Edge>> terrain(
		CalculationContext context, CombatPositioning.Cell origin, double slowdown) {
		var graph = new LinkedHashMap<CombatPositioning.Cell, List<CombatPositioning.Edge>>();
		var pending = new ArrayDeque<CombatPositioning.Cell>();
		graph.put(origin, List.of());
		pending.add(origin);
		while (!pending.isEmpty()) {
			var from = pending.removeFirst();
			var accepted = new ArrayList<CombatPositioning.Edge>();
			for (var edge : edges(context, from, slowdown)) {
				var to = edge.destination();
				if (Math.abs(to.x() - origin.x()) > RADIUS || Math.abs(to.z() - origin.z()) > RADIUS
					|| Math.abs(to.y() - origin.y()) > 2) continue;
				if (!graph.containsKey(to)) {
					if (graph.size() >= MAX_CELLS) continue;
					graph.put(to, List.of());
					pending.add(to);
				}
				accepted.add(edge);
			}
			graph.put(from, List.copyOf(accepted));
		}
		return Map.copyOf(graph);
	}

	private static List<CombatPositioning.Edge> edges(CalculationContext context, CombatPositioning.Cell from, double slowdown) {
		var edges = new ArrayList<CombatPositioning.Edge>();
		var source = new BetterBlockPos(from.x(), from.y(), from.z());
		var result = new MutableMoveResult();
		for (Moves move : MOVES) {
			// Check loaded columns before asking Baritone, which can also consult cached unloaded chunks.
			if (!context.isLoaded(from.x(), from.z()) || !context.isLoaded(from.x() + move.xOffset, from.z() + move.zOffset)) continue;
			result.reset();
			move.apply(context, from.x(), from.y(), from.z(), result);
			if (!Double.isFinite(result.cost) || result.cost <= 0 || result.cost > CombatPositioning.HORIZON_TICKS
				|| Math.abs(result.y - from.y()) > 1) continue;
			if (!WorldTravelPolicy.permitsMovement(context.world, from.x(), from.y(), from.z(), result.x, result.y, result.z,
				result.y > from.y())) continue;
			if (!dryAndSafe(context, result.x, result.y, result.z)) continue;
			var movement = move.apply0(context, source);
			if (!movement.toBreak(context.bsi).isEmpty() || !movement.toPlace(context.bsi).isEmpty()) continue;
			edges.add(new CombatPositioning.Edge(new CombatPositioning.Cell(result.x, result.y, result.z), result.cost * slowdown));
		}
		return List.copyOf(edges);
	}

	private static boolean dryAndSafe(CalculationContext context, int x, int y, int z) {
		for (int dy = -1; dy <= 1; dy++) {
			var state = context.get(x, y + dy, z);
			if (!state.getFluidState().isEmpty() || MovementHelper.avoidWalkingInto(state)) return false;
		}
		return true;
	}

	private static CombatPositioning.Cell cell(BlockPos pos) { return new CombatPositioning.Cell(pos.getX(), pos.getY(), pos.getZ()); }
}
