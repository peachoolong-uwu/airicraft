package ai.moeru.airicraft.agent.spatial;

import net.minecraft.block.BlockState;
import net.minecraft.block.MushroomBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

/** Shared surface definition for observations and acquisition constraints. */
public final class SurfaceTerrain {
	private SurfaceTerrain() {}

	public static int groundY(World world, BlockPos column) {
		int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()) - 1;
		while (y > world.getBottomY()) {
			BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
			BlockState state = world.getBlockState(pos);
			if (!state.getFluidState().isEmpty()) break;
			if (!state.isIn(BlockTags.LOGS) && !state.isIn(BlockTags.LEAVES) && !(state.getBlock() instanceof MushroomBlock)
				&& !state.getCollisionShape(world, pos).isEmpty()) break;
			y--;
		}
		return y;
	}
}
