package ai.moeru.airicraft.agent.tasks;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Work spaces next to a known source; the source itself is left for exact harvesting. */
final class AcquisitionExcavationSites {
	private AcquisitionExcavationSites() {}

	static List<BlockPos> find(BlockPos source, Predicate<BlockPos> clearable, Predicate<BlockPos> supported) {
		List<BlockPos> sites = new ArrayList<>();
		for (Direction side : Direction.Type.HORIZONTAL) {
			BlockPos beside = source.offset(side);
			for (BlockPos feet : List.of(beside, beside.down())) {
				if (clearable.test(feet) && clearable.test(feet.up()) && supported.test(feet.down())) sites.add(feet);
			}
		}
		return List.copyOf(sites);
	}
}
