package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.AcquisitionConstraints;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import ai.moeru.airicraft.agent.spatial.SurfaceTerrain;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static ai.moeru.airicraft.agent.tasks.TargetAcquisitionTaskExecutor.*;

/** Samples loaded client facts and performs exact interactions on the client tick. */
final class MinecraftAcquisitionEnvironment implements Environment {
	private BlockPos breaking;
	private MinecraftClient client() { return MinecraftClient.getInstance(); }
	@Override public GoalPosition position() { return position(client().player.getBlockPos()); }
	@Override public int inventoryCount(GoalMineSpec spec) {
		int count = 0;
		var inventory = client().player.getInventory();
		for (int i = 0; i < inventory.size(); i++) {
			var stack = inventory.getStack(i);
			if (spec.matchingItemIds().contains(Registries.ITEM.getId(stack.getItem()).toString())) count += stack.getCount();
		}
		return count;
	}
	@Override public boolean inScope(GoalPosition position, AcquisitionConstraints constraints, boolean standing) {
		BlockPos pos = block(position);
		if (!constraints.contains(position) || !client().world.isChunkLoaded(pos)) return false;
		return !constraints.surfaceOnly() || pos.getY() >= surfaceGroundY(pos) + (standing ? 1 : 0);
	}

	private int surfaceGroundY(BlockPos column) {
		return SurfaceTerrain.groundY(client().world, column);
	}

	@Override public List<Candidate> candidates(GoalMineSpec spec, AcquisitionConstraints constraints, Set<String> rejected) {
		var world = client().world;
		BlockPos center = block(constraints.center());
		List<Candidate> result = new ArrayList<>();
		for (ItemEntity item : world.getEntitiesByClass(ItemEntity.class,
			new Box(center).expand(constraints.radius(), constraints.verticalRadius(), constraints.radius()), ItemEntity::isAlive)) {
			if (!spec.matchingItemIds().contains(Registries.ITEM.getId(item.getStack().getItem()).toString())) continue;
			GoalPosition pos = position(item.getBlockPos());
			if (!inScope(pos, constraints, true)) continue;
			Candidate drop = new Candidate(Kind.DROP, item.getUuidAsString(), pos, pos);
			if (!rejected.contains(drop.key())) result.add(drop);
		}
		List<BlockPos> blocks = new ArrayList<>();
		for (BlockPos cursor : BlockPos.iterate(center.add(-constraints.radius(), -constraints.verticalRadius(), -constraints.radius()),
			center.add(constraints.radius(), constraints.verticalRadius(), constraints.radius()))) {
			if (!world.isChunkLoaded(cursor) || !constraints.contains(position(cursor))) continue;
			BlockState state = world.getBlockState(cursor);
			if (spec.blockIds().contains(id(state)) && HarvestableBlocks.ready(state)
				&& inScope(position(cursor), constraints, false)) blocks.add(cursor.toImmutable());
		}
		blocks.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(client().player.getPos())));
		for (BlockPos pos : blocks) {
			String blockId = id(world.getBlockState(pos));
			for (GoalPosition work : workPositions(pos, constraints)) {
				Candidate candidate = new Candidate(Kind.BLOCK, blockId, position(pos), work);
				if (!rejected.contains(candidate.key())) result.add(candidate);
			}
			if (result.size() >= 32) break;
		}
		result.sort(Comparator.comparing(Candidate::kind)
			.thenComparingDouble(value -> distanceSquared(position(), value.workPosition()))
			.thenComparing(Candidate::key));
		return result;
	}

	private List<GoalPosition> workPositions(BlockPos source, AcquisitionConstraints constraints) {
		List<GoalPosition> sites = new ArrayList<>();
		GoalPosition open = workPosition(source, constraints);
		if (open != null) sites.add(open);
		// Navigation can carve its destination's feet/head space. Requiring air here
		// would discard fully enclosed ore before A* ever has a chance to approach it.
		for (BlockPos pos : AcquisitionExcavationSites.find(source,
			candidate -> inScope(position(candidate), constraints, true) && clearable(candidate),
			this::safeSupport)) {
			GoalPosition site = position(pos);
			if (!sites.contains(site)) sites.add(site);
		}
		return sites;
	}

	private boolean clearable(BlockPos pos) {
		var world = client().world;
		BlockState state = world.getBlockState(pos);
		return state.getFluidState().isEmpty() && !state.hasBlockEntity()
			&& state.getHardness(world, pos) >= 0 && !hazardous(state);
	}

	private boolean safeSupport(BlockPos pos) {
		var world = client().world;
		if (!world.isChunkLoaded(pos)) return false;
		BlockState state = world.getBlockState(pos);
		return state.getFluidState().isEmpty() && !hazardous(state)
			&& state.isSideSolidFullSquare(world, pos, net.minecraft.util.math.Direction.UP);
	}

	private static boolean hazardous(BlockState state) {
		return Set.of("minecraft:cactus", "minecraft:magma_block", "minecraft:campfire", "minecraft:soul_campfire",
			"minecraft:fire", "minecraft:soul_fire", "minecraft:lava", "minecraft:powder_snow").contains(id(state));
	}

	private GoalPosition workPosition(BlockPos target, AcquisitionConstraints constraints) {
		if (inScope(position(), constraints, true) && interactionPath(client().player.getEyePos(), target) != null) return position();
		List<BlockPos> sites = new ArrayList<>();
		for (BlockPos cursor : workPositions(target)) {
			if (inScope(position(cursor), constraints, true) && standable(cursor)
				&& interactionPath(Vec3d.ofBottomCenter(cursor).add(0, 1.62, 0), target) != null) sites.add(cursor.toImmutable());
		}
		return sites.stream().min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(client().player.getPos())))
			.map(MinecraftAcquisitionEnvironment::position).orElse(null);
	}

	static Iterable<BlockPos> workPositions(BlockPos target) {
		// Feet can be five blocks below a source while its center is within eye reach.
		return BlockPos.iterate(target.add(-3, -5, -3), target.add(3, 3, 3));
	}

	private boolean standable(BlockPos pos) {
		var world = client().world;
		return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()
			&& world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty()
			&& world.getBlockState(pos).getFluidState().isEmpty()
			&& safeSupport(pos.down());
	}

	/** Leaves may be cleared explicitly; other occluders are not acquisition targets. */
	private BlockHitResult interactionPath(Vec3d eye, BlockPos target) {
		if (eye.squaredDistanceTo(Vec3d.ofCenter(target)) > 20.25) return null;
		BlockHitResult hit = client().world.raycast(new RaycastContext(eye, Vec3d.ofCenter(target),
			RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client().player));
		if (hit.getType() != HitResult.Type.BLOCK) return null;
		return hit.getBlockPos().equals(target) || client().world.getBlockState(hit.getBlockPos()).isIn(BlockTags.LEAVES) ? hit : null;
	}

	@Override public boolean targetPresent(Candidate target) {
		if (target.kind() == Kind.BLOCK) return client().world.isChunkLoaded(block(target.position()))
			&& id(client().world.getBlockState(block(target.position()))).equals(target.id())
			&& HarvestableBlocks.ready(client().world.getBlockState(block(target.position())));
		return client().world.getEntitiesByClass(ItemEntity.class, new Box(block(target.position())).expand(3),
			item -> item.isAlive() && item.getUuidAsString().equals(target.id())).size() > 0;
	}
	@Override public boolean canInteract(Candidate target) {
		if (!client().player.isOnGround()) return false;
		if (target.kind() == Kind.DROP) return client().world.getEntitiesByClass(ItemEntity.class,
			new Box(block(target.position())).expand(3), item -> item.isAlive() && item.getUuidAsString().equals(target.id())
				&& client().player.squaredDistanceTo(item) <= 1).size() > 0;
		return interactionPath(client().player.getEyePos(), block(target.position())) != null;
	}
	@Override public BreakResult breakTarget(Candidate target, GoalMineSpec spec) {
		var client = client();
		if (!targetPresent(target)) return BreakResult.BROKEN;
		if (client.player.currentScreenHandler != client.player.playerScreenHandler
			|| !client.player.currentScreenHandler.getCursorStack().isEmpty()) return BreakResult.FAILED;
		BlockHitResult hit = interactionPath(client.player.getEyePos(), block(target.position()));
		if (hit == null) return BreakResult.FAILED;
		BlockPos pos = hit.getBlockPos();
		if (!pos.equals(breaking)) {
			cancelBreaking();
			var result = BaritoneTaskExecutor.MiningToolPreflight.ensureSelected(client, client.player,
				List.of(client.world.getBlockState(pos)), pos.equals(block(target.position())) ? spec.requiredToolItemIds() : List.of());
			if (!result.ok()) return BreakResult.FAILED;
			if (!client.interactionManager.attackBlock(pos, hit.getSide())) return BreakResult.FAILED;
			breaking = pos;
		}
		client.interactionManager.updateBlockBreakingProgress(pos, hit.getSide());
		client.player.swingHand(Hand.MAIN_HAND);
		return targetPresent(target) ? BreakResult.BREAKING : BreakResult.BROKEN;
	}
	@Override public void cancelBreaking() {
		if (breaking != null && client().interactionManager != null) client().interactionManager.cancelBlockBreaking();
		breaking = null;
	}
	private static String id(BlockState state) { return Registries.BLOCK.getId(state.getBlock()).toString(); }
	private static BlockPos block(GoalPosition p) { return new BlockPos(p.x(), p.y(), p.z()); }
	private static GoalPosition position(BlockPos p) { return new GoalPosition(p.getX(), p.getY(), p.getZ(), true); }
}
