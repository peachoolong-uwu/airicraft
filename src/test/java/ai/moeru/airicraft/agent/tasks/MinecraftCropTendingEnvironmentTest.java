package ai.moeru.airicraft.agent.tasks;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftCropTendingEnvironmentTest {
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
