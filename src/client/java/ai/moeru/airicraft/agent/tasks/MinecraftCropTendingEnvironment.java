package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Comparator;
import java.util.stream.StreamSupport;

import static ai.moeru.airicraft.agent.tasks.CropTendingTaskExecutor.Cell;

final class MinecraftCropTendingEnvironment implements CropTendingTaskExecutor.Environment {
	private final CameraController camera;
	MinecraftCropTendingEnvironment(CameraController camera) { this.camera = camera; }
	private MinecraftClient client() { return MinecraftClient.getInstance(); }
	private static BlockPos block(GoalPosition pos) { return new BlockPos(pos.x(), pos.y(), pos.z()); }
	private static GoalPosition position(BlockPos pos) { return new GoalPosition(pos.getX(), pos.getY(), pos.getZ(), true); }

	@Override public String validate(CropTendingStepArgs args) {
		var client = client();
		if (client.world == null || client.player == null) return "world_unavailable";
		var item = Registries.ITEM.get(Identifier.of(args.seedItemId()));
		if (!(item instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof CropBlock)) return "unsupported_crop_planting_item";
		for (int x : new int[]{args.x1(), args.x2()}) for (int z : new int[]{args.z1(), args.z2()}) {
			if (client.player.squaredDistanceTo(Vec3d.ofCenter(new BlockPos(x, args.y(), z))) > 64 * 64) return "crop_plot_too_far";
		}
		return null;
	}

	@Override public Cell state(GoalPosition position, CropTendingStepArgs args) {
		var world = client().world;
		BlockPos pos = block(position);
		if (world == null || !world.isChunkLoaded(pos)) return Cell.UNLOADED;
		var state = world.getBlockState(pos);
		var item = Registries.ITEM.get(Identifier.of(args.seedItemId()));
		if (item instanceof BlockItem seed && state.isOf(seed.getBlock()) && state.getBlock() instanceof CropBlock crop)
			return crop.isMature(state) ? Cell.MATURE : Cell.GROWING;
		return state.isAir() && world.getBlockState(pos.down()).isOf(Blocks.FARMLAND) ? Cell.EMPTY_FARMLAND : Cell.OTHER;
	}

	@Override public GoalPosition position() { return position(client().player.getBlockPos()); }

	@Override public GoalPosition workPosition(GoalPosition crop) {
		if (canHarvest(crop)) return position();
		var world = client().world;
		BlockPos target = block(crop);
		return StreamSupport.stream(BlockPos.iterate(target.add(-3, -1, -3), target.add(3, 1, 3)).spliterator(), false)
			.filter(pos -> world.isChunkLoaded(pos) && world.getBlockState(pos).isAir() && world.getBlockState(pos.up()).isAir()
				&& world.getBlockState(pos.down()).isSideSolidFullSquare(world, pos.down(), Direction.UP)
				&& !world.getBlockState(pos.down()).isOf(Blocks.FARMLAND)
				&& visible(Vec3d.ofBottomCenter(pos).add(0, 1.62, 0), target))
			.map(BlockPos::toImmutable).min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(client().player.getPos())))
			.map(MinecraftCropTendingEnvironment::position).orElse(null);
	}

	private boolean visible(Vec3d eye, BlockPos target) {
		if (eye.squaredDistanceTo(Vec3d.ofCenter(target)) > 20.25) return false;
		var hit = client().world.raycast(new RaycastContext(eye, Vec3d.ofCenter(target), RaycastContext.ShapeType.OUTLINE,
			RaycastContext.FluidHandling.NONE, client().player));
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
	}

	@Override public boolean canHarvest(GoalPosition crop) {
		return client().player.isOnGround() && visible(client().player.getEyePos(), block(crop));
	}

	@Override public boolean harvest(GoalPosition crop, CropTendingStepArgs args) {
		var client = client();
		if (state(crop, args) != Cell.MATURE || !canHarvest(crop) || client.interactionManager == null
			|| client.player.currentScreenHandler != client.player.playerScreenHandler
			|| !client.player.currentScreenHandler.getCursorStack().isEmpty()) return false;
		BlockPos pos = block(crop);
		camera.lookAtNow(client, Vec3d.ofCenter(pos));
		client.interactionManager.attackBlock(pos, Direction.UP);
		client.player.swingHand(Hand.MAIN_HAND);
		return state(crop, args) == Cell.EMPTY_FARMLAND;
	}

	@Override public int seedCount(String seedItemId) {
		int count = 0;
		var inventory = client().player.getInventory();
		for (int i = 0; i < inventory.size(); i++) {
			var stack = inventory.getStack(i);
			if (Registries.ITEM.getId(stack.getItem()).toString().equals(seedItemId)) count += stack.getCount();
		}
		return count;
	}
}
