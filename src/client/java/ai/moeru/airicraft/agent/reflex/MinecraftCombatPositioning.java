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
	private CombatPositioning.Cell anchor;
	private String plannedFocus;
	private Vec3d plannedFocusVelocity = Vec3d.ZERO;
	private boolean plannedAttackReady;
	private boolean plannedShielding;
	private long plannedTick = Long.MIN_VALUE;
	private CombatPositioning.Decision decision;
	private int terrainCells;
	private List<CombatPositioning.Splash> incomingSplashes = List.of();
	private long planningNanos;
	private CombatTraversal traversal;
	private Vec3d preciseWaypoint;
	private double desiredDistance = 2.6;
	private List<CombatPositioning.Threat> liveThreats;
	private List<CombatPositioning.Threat> plannedThreats;

	CombatPositioning.Decision plan(MinecraftClient client, List<LivingEntity> entities, LivingEntity focus, long tick, boolean shielding, double fightingDistance) {
		if (desiredDistance != fightingDistance) invalidate();
		desiredDistance = fightingDistance;
		List<CombatPositioning.Threat> threats = new ArrayList<>();
		CombatPositioning.Threat focusThreat = null;
		for (LivingEntity entity : entities) {
			var attribute = entity.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED);
			double speed = Math.max(entity.getVelocity().horizontalLength(), attribute == null ? .1 : attribute.getValue() * 1.3);
			Observation old = previous.put(entity.getUuidAsString(), new Observation(entity.getPos(), tick));
			if (old != null && tick > old.tick()) speed = Math.max(speed,
				entity.getPos().subtract(old.position()).horizontalLength() / (tick - old.tick()));
			threats.add(new CombatPositioning.Threat(entity.getX(), entity.getY(), entity.getZ(), speed,
				2.4 + Math.max(0, (entity.getWidth() - .6) / 2), SurvivalReflexRuntime.isRangedThreat(entity), entity.getVelocity().x, entity.getVelocity().z));
			if (entity == focus) {
				var t = threats.getLast();
				focusThreat = new CombatPositioning.Threat(t.x(), t.y(), t.z(), t.blocksPerTick(), t.reach(), t.ranged(), t.velocityX(), t.velocityZ(), desiredDistance);
			}
		}
		liveThreats = List.copyOf(threats);
		previous.keySet().retainAll(entities.stream().map(LivingEntity::getUuidAsString).toList());
		if (traversal != null && traversal.destination() != null && decision != null) return decision;
		var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
		BlockPos feet = baritone.getPlayerContext().playerFeet();
		CombatPositioning.Cell origin = cell(feet);
		if (anchor == null) anchor = origin;
		boolean attackReady = !shielding && client.player.getAttackCooldownProgress(0) >= .92F;
		incomingSplashes = predictSplashes(client);
		if (incomingSplashes.isEmpty() && plannedThreats != null && !CombatPositioning.crowdMoved(plannedThreats, liveThreats) && decision != null && focus.getUuidAsString().equals(plannedFocus) && attackReady == plannedAttackReady && shielding == plannedShielding && tick - plannedTick < REPLAN_TICKS
			&& focus.getVelocity().subtract(plannedFocusVelocity).horizontalLengthSquared() < .01
			&& !origin.equals(decision.nextStep())) return decision;
		long started = System.nanoTime();
		var context = new CalculationContext(baritone);

		// Facing the pack uses walking/backpedaling, never a forward sprint away from it.
		double slowdown = (context.canSprint ? 1.3 : 1) * (shielding ? 5 : 1);
		Map<CombatPositioning.Cell, List<CombatPositioning.Edge>> graph = terrain(context, origin, slowdown);
		terrainCells = graph.size();
		decision = CombatPositioning.choose(origin, graph, threats, decision == null ? null : decision.nextStep(), anchor, attackReady, focusThreat, incomingSplashes);
		plannedThreats = liveThreats;
		plannedTick = tick;
		plannedFocus = focus.getUuidAsString();
		plannedFocusVelocity = focus.getVelocity();
		plannedAttackReady = attackReady;
		plannedShielding = shielding;
		planningNanos = System.nanoTime() - started;
		return decision;
	}

	private static List<CombatPositioning.Splash> predictSplashes(MinecraftClient client) {
		var result = new ArrayList<CombatPositioning.Splash>();
		for (var potion : client.world.getEntitiesByClass(net.minecraft.entity.projectile.thrown.PotionEntity.class,
			client.player.getBoundingBox().expand(16), e -> e.isAlive() && e.getOwner() != client.player)) {
			Vec3d position = potion.getPos(), velocity = potion.getVelocity();
			for (int tick = 1; tick <= 24; tick++) {
				// Vanilla 1.21.8 applies gravity and drag before moving the thrown entity.
				if (!potion.hasNoGravity()) velocity = velocity.add(0, -.05, 0);
				boolean water = client.world.getFluidState(BlockPos.ofFloored(position)).isIn(net.minecraft.registry.tag.FluidTags.WATER);
				velocity = velocity.multiply(water ? .8F : .99F);
				Vec3d next = position.add(velocity);
				var hit = client.world.raycast(new net.minecraft.world.RaycastContext(position, next,
					net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, potion));
				if (hit.getType() != net.minecraft.util.hit.HitResult.Type.MISS) {
					result.add(new CombatPositioning.Splash(hit.getPos().x, hit.getPos().y, hit.getPos().z, tick));
					break;
				}
				var contact = client.player.getBoundingBox().expand(.3).raycast(position, next);
				if (contact.isPresent()) {
					Vec3d point = contact.get();
					result.add(new CombatPositioning.Splash(point.x, point.y, point.z, tick));
					break;
				}
				position = next;
			}
		}
		return List.copyOf(result);
	}

	Map<String, Object> evidence() {
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("decision", decision);
		evidence.put("traversal", traversal == null ? Map.of("phase", "idle") : traversal.evidence());
		evidence.put("preciseWaypoint", preciseWaypoint == null ? null : new double[]{preciseWaypoint.x, preciseWaypoint.y, preciseWaypoint.z});
		evidence.put("incomingSplashes", incomingSplashes);
		evidence.put("anchor", anchor);
		evidence.put("focusUuid", plannedFocus);
		evidence.put("attackReady", plannedAttackReady);
		evidence.put("desiredDistance", desiredDistance);
		evidence.put("crowd", liveThreats);
		evidence.put("terrainCells", terrainCells);
		evidence.put("planningMicros", planningNanos / 1000);
		evidence.put("plannedTick", plannedTick);
		evidence.put("threatCount", previous.size());
		return evidence;
	}

	/** Fresh validation prevents a cached escape hop surviving a block change or knockback. */
	boolean canStepTo(CombatPositioning.Cell target) {
		if (traversal != null && target.equals(traversal.destination())) return true;
		var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
		var context = new CalculationContext(baritone);
		var origin = cell(baritone.getPlayerContext().playerFeet());
		if (origin.equals(target)) return true;
		return edges(context, origin, 1).stream().anyMatch(edge -> edge.destination().equals(target));
	}

	void invalidate() { plannedTick = Long.MIN_VALUE; decision = null; }

	/** Quantized keys must not cut a corner or step sideways off the validated route. */
	CombatPositioning.Steering steering(MinecraftClient client, CombatPositioning.Cell target, Vec3d waypoint, Vec3d facing) {
		var player = client.player;
		Vec3d forward = new Vec3d(facing.x - player.getX(), 0, facing.z - player.getZ()).normalize();
		Vec3d left = new Vec3d(forward.z, 0, -forward.x);
		if (player.getPos().subtract(waypoint).horizontalLengthSquared() < .0064) return new CombatPositioning.Steering(false, false, false, false);
		Vec3d desired = new Vec3d(waypoint.x - player.getX(), 0, waypoint.z - player.getZ()).normalize();
		var preferred = CombatPositioning.steering(player.getX(), player.getZ(), waypoint.x, waypoint.z, facing.x, facing.z);
		CombatPositioning.Steering best = new CombatPositioning.Steering(false, false, false, false);
		double bestProgress = 0;
		for (int f = -1; f <= 1; f++) for (int l = -1; l <= 1; l++) {
			if (f == 0 && l == 0) continue;
			Vec3d motion = forward.multiply(f).add(left.multiply(l)).normalize();
			var keys = new CombatPositioning.Steering(f > 0, f < 0, l > 0, l < 0);
			double progress = motion.dotProduct(desired) + (keys.equals(preferred) ? .001 : 0);
			if (progress <= bestProgress) continue;
			Vec3d probe = player.getPos().add(motion.multiply(.35));
			BlockPos cell = BlockPos.ofFloored(probe);
			if ((cell.getX() != player.getBlockX() || cell.getZ() != player.getBlockZ())
				&& (cell.getX() != target.x() || cell.getZ() != target.z())) continue;
			double rise = Math.max(0, target.y() - player.getY());
			if (!client.world.isSpaceEmpty(player, player.getBoundingBox().offset(motion.x * .35, rise, motion.z * .35))) continue;
			best = keys;
			bestProgress = progress;
		}
		return best;
	}

	CombatTraversal.Control control(MinecraftClient client, CombatPositioning.Cell target, Vec3d facing, Vec3d focus, long tick) {
		var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
		var context = new CalculationContext(baritone);
		if (traversal == null) traversal = new CombatTraversal();
		if (traversal.destination() == null && target.y() != client.player.getBlockY() && client.player.isOnGround()) {
			var source = baritone.getPlayerContext().playerFeet();
			var result = new MutableMoveResult();
			for (Moves move : MOVES) {
				result.reset();
				move.apply(context, source.x, source.y, source.z, result);
				if (result.x == target.x() && result.y == target.y() && result.z == target.z() && Double.isFinite(result.cost)) {
					var movement = move.apply0(context, source);
					if (movement.toBreak(context.bsi).isEmpty() && movement.toPlace(context.bsi).isEmpty()) {
						traversal.start(movement, target, tick);
						break;
					}
				}
			}
		}
		if (traversal.destination() != null) {
			preciseWaypoint = null;
			var control = traversal.tick(client, context, facing, tick);
			if (traversal.destination() == null) invalidate();
			return control;
		}
		preciseWaypoint = preciseWaypoint(client, context, target, focus);
		return new CombatTraversal.Control(steering(client, target, preciseWaypoint, facing), false, false);
	}

	private Vec3d preciseWaypoint(MinecraftClient client, CalculationContext context, CombatPositioning.Cell target, Vec3d focus) {
		var player = client.player;
		Vec3d best = player.getPos();
		double bestScore = Double.POSITIVE_INFINITY;
		for (double ox : new double[]{.32, .5, .68}) for (double oz : new double[]{.32, .5, .68}) {
			Vec3d point = new Vec3d(target.x() + ox, target.y(), target.z() + oz);
			if (!dryAndSafe(context, target.x(), target.y(), target.z())) continue;
			if (!client.world.isSpaceEmpty(player, player.getBoundingBox().offset(point.subtract(player.getPos())))) continue;
			double score = CombatPositioning.preciseScore(point.x, point.y, point.z, focus.x, focus.y, focus.z, incomingSplashes, desiredDistance, liveThreats);
			score += point.distanceTo(player.getPos()) * .1;
			if (score < bestScore) { best = point; bestScore = score; }
		}
		return best;
	}

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
					|| Math.abs(to.y() - origin.y()) > 3) continue;
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
				|| result.y - from.y() > 1 || from.y() - result.y > 3) continue;
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
			// Direct steering cannot open a door as Baritone's full movement executor can.
			if (dy >= 0 && !state.getCollisionShape(context.world, new BlockPos(x, y + dy, z)).isEmpty()) return false;
		}
		return true;
	}

	private static CombatPositioning.Cell cell(BlockPos pos) { return new CombatPositioning.Cell(pos.getX(), pos.getY(), pos.getZ()); }
}
