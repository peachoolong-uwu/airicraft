package ai.moeru.airicraft.agent.tasks;

import net.minecraft.block.BlockState;
import net.minecraft.block.CropBlock;

/** Generic resource acquisition preserves growing crops; exact terrain edits remain explicit. */
public final class HarvestableBlocks {
	private HarvestableBlocks() {}

	public static boolean ready(BlockState state) {
		return !(state.getBlock() instanceof CropBlock crop) || crop.isMature(state);
	}
}
