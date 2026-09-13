package ai.moeru.airicraft.agent.tasks;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Nearby landings can collect a drop during approach without standing in its cell. */
final class AcquisitionPickupSites {
	private AcquisitionPickupSites() {}

	static List<BlockPos> find(BlockPos drop, Predicate<BlockPos> standable) {
		List<BlockPos> sites = new ArrayList<>();
		for (BlockPos feet : BlockPos.iterate(drop.add(-1, 0, -1), drop.add(1, 1, 1))) {
			if (standable.test(feet)) sites.add(feet.toImmutable());
		}
		return List.copyOf(sites);
	}
}
