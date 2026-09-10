package ai.moeru.airicraft.agent.tasks;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AcquisitionExcavationSitesTest {
	@Test void fullyBuriedSourceHasWorkSpacesWithoutClearingTheOre() {
		BlockPos ore = new BlockPos(206,98,455);
		var sites = AcquisitionExcavationSites.find(ore, pos -> !pos.equals(ore), pos -> true);
		assertEquals(8, sites.size());
		for (var feet : sites) {
			assertNotEquals(ore, feet);
			assertNotEquals(ore, feet.up());
			assertTrue(net.minecraft.util.math.Vec3d.ofBottomCenter(feet).add(0,1.62,0)
				.squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(ore)) < 20.25);
		}
	}

	@Test void requiresBothHeadroomAndSafeSupportInsideTheAllowedSpace() {
		BlockPos ore = new BlockPos(0,64,0);
		var allowed = Set.of(ore.east(), ore.east().up());
		assertEquals(java.util.List.of(ore.east()), AcquisitionExcavationSites.find(ore, allowed::contains, pos -> true));
		assertTrue(AcquisitionExcavationSites.find(ore, allowed::contains, pos -> false).isEmpty());
		assertTrue(AcquisitionExcavationSites.find(ore, pos -> pos.equals(ore.east()), pos -> true).isEmpty());
	}
}
