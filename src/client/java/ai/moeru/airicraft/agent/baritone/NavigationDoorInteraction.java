package ai.moeru.airicraft.agent.baritone;

import baritone.api.utils.IPlayerContext;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementState;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.WeakHashMap;

/** Uses Baritone's ordinary look/use inputs, including for a door in the source cell. */
public final class NavigationDoorInteraction {
	private static final WeakHashMap<IPlayerContext, NavigationDoorInteraction> CONTROLLERS = new WeakHashMap<>();
	private World world;
	private PendingDoor pending;
	private record PendingDoor(BlockPos pos, BlockState original, Box panel, Vec3d travel) {}

	public static boolean update(IPlayerContext ctx, BlockPos src, BlockPos dest, MovementState state) {
		return CONTROLLERS.computeIfAbsent(ctx, ignored -> new NavigationDoorInteraction()).tick(ctx, src, dest, state);
	}

	private boolean tick(IPlayerContext ctx, BlockPos src, BlockPos dest, MovementState state) {
		if (world != ctx.world()) {
			world = ctx.world();
			pending = null;
		}
		Box body = ctx.player().getBoundingBox();

		Vec3d travel = new Vec3d(dest.getX() - src.getX(), 0, dest.getZ() - src.getZ());
		if (travel.lengthSquared() == 0) return false;
		// Cover both endpoint panels, including the far edge of the destination cell.
		Vec3d sweep = travel.normalize().multiply(travel.length() + 0.8);
		for (BlockPos candidate : new BlockPos[]{src, src.up(), dest, dest.up()}) {
			BlockState door = world.getBlockState(candidate);
			if (!(door.getBlock() instanceof DoorBlock block) || !block.getBlockSetType().canOpenByHand()) continue;
			BlockPos pos = door.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? candidate.down() : candidate;
			door = world.getBlockState(pos);
			if (!(door.getBlock() instanceof DoorBlock)) continue;
			Box panel = door.getCollisionShape(world, pos).getBoundingBox().offset(pos).stretch(0, 1, 0);
			if (!DoorPassage.blocks(body, sweep, panel)) continue;
			// A panel running alongside travel is not a door we can open out of the way.
			Box toggled = door.cycle(DoorBlock.OPEN).getCollisionShape(world, pos).getBoundingBox().offset(pos).stretch(0, 1, 0);
			if (DoorPassage.blocks(body, sweep, toggled)) continue;
			if (use(ctx, pos, state)) {
				if (pending == null) pending = new PendingDoor(pos.toImmutable(), door, panel, travel);
				return true;
			}
		}
		return false;
	}

	/** Arrival may end movement before the player's trailing edge clears the panel. */
	public static void restorePassedDoor(IPlayerContext ctx) {
		NavigationDoorInteraction controller = CONTROLLERS.get(ctx);
		if (controller == null || controller.pending == null) return;
		PendingDoor door = controller.pending;
		if (controller.world != ctx.world() || ctx.player() == null
			|| !ctx.world().getBlockState(door.pos()).equals(door.original().cycle(DoorBlock.OPEN))) {
			controller.pending = null;
			return;
		}
		if (!DoorPassage.cleared(ctx.player().getBoundingBox(), door.travel(), door.panel())) return;
		if (ctx.player().isSneaking() || ctx.player().isUsingItem()) return;
		double reach = ctx.playerController().getBlockReachDistance();
		var rotation = RotationUtils.reachable(ctx, door.pos(), reach);
		if (rotation.isPresent()) {
			var hit = baritone.api.utils.RayTraceUtils.rayTraceTowards(ctx.player(), rotation.get(), reach);
			if (hit instanceof net.minecraft.util.hit.BlockHitResult blockHit
				&& (blockHit.getBlockPos().equals(door.pos()) || blockHit.getBlockPos().equals(door.pos().up()))) {
				ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), net.minecraft.util.Hand.MAIN_HAND, blockHit);
			}
		}
		controller.pending = null;
	}

	private static boolean use(IPlayerContext ctx, BlockPos pos, MovementState state) {
		var rotation = RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance());
		if (rotation.isEmpty()) return false;
		state.getInputStates().clear();
		state.setInput(Input.SNEAK, false);
		state.setTarget(new MovementState.MovementTarget(rotation.get(), true));
		// Wait until the look target is selected, so another block is never clicked.
		if (ctx.isLookingAt(pos) || ctx.isLookingAt(pos.up())) state.setInput(Input.CLICK_RIGHT, true);
		return true;
	}
}
