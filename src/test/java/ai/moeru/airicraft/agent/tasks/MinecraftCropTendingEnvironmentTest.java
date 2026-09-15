package ai.moeru.airicraft.agent.tasks;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftCropTendingEnvironmentTest {
	@Test void approachCellRemainsInReachWhenNavigationStopsAtItsEdge() {
		var potatoAim = new Vec3d(-305.5, 64.5, -384.5);
		var oldCenter = new Vec3d(-302.5, 65.62, -381.5);
		var recordedArrival = new Vec3d(-302.2157860308709, 65.62, -381.2924207031525);
		assertTrue(oldCenter.squaredDistanceTo(potatoAim) < 4.5 * 4.5);
		assertTrue(recordedArrival.squaredDistanceTo(potatoAim) > 4.5 * 4.5);
		assertFalse(MinecraftCropTendingEnvironment.reachableFromWholeCell(oldCenter, potatoAim));
		var closerCenter = new Vec3d(-303.5, 65.62, -381.5);
		assertTrue(MinecraftCropTendingEnvironment.reachableFromWholeCell(closerCenter, potatoAim));
		for (double x : new double[]{-0.5, 0.5}) for (double z : new double[]{-0.5, 0.5})
			assertTrue(closerCenter.add(x, 0, z).squaredDistanceTo(potatoAim) <= 4.5 * 4.5);
	}

	@Test void scatteredDropsUseTheirOwnLevelExceptOnShortenedFarmland() {
		var drop = new BlockPos(261, 62, 478);
		assertEquals(new ai.moeru.airicraft.agent.goals.GoalPosition(261, 62, 478, true),
			MinecraftCropTendingEnvironment.pickupGoal(drop, false), "Irrigation water is below the crop walking level");
		assertEquals(new ai.moeru.airicraft.agent.goals.GoalPosition(261, 63, 478, true),
			MinecraftCropTendingEnvironment.pickupGoal(drop, true), "Farmland drops occupy the soil block");
	}

	@Test void aimsInsideShortCropInsteadOfAtItsTopBoundary() {
		// A mature beetroot outline is half a block tall.
		var shape = VoxelShapes.cuboid(0, 0, 0, 1, 0.5, 1);
		var pos = new BlockPos(260, 63, 484);
		var eye = new Vec3d(262.5, 64.62, 484.5);
		assertNull(shape.raycast(eye, Vec3d.ofCenter(pos), pos));
		var hit = shape.raycast(eye, MinecraftCropTendingEnvironment.cropAim(pos, shape), pos);
		assertNotNull(hit);
		assertEquals(pos, hit.getBlockPos());
	}
}
