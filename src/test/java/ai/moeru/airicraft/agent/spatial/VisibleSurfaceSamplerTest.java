package ai.moeru.airicraft.agent.spatial;

import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class VisibleSurfaceSamplerTest {
	private static final Vec3d EYE = new Vec3d(0.5, 0.5, 0.5);
	private static final BlockPos ORE = new BlockPos(4, 0, 0);
	private static final BlockPos WALL = new BlockPos(2, 0, 0);

	@Test void occluderHidesOreUntilTheNextSampleAfterRemoval() {
		var ore = new Surface(ORE, new Box(ORE));
		var wall = new Surface(WALL, new Box(2, -3, -3, 3, 4, 4));
		var hidden = sample(8, List.of(wall, ore));
		assertTrue(hidden.contains(WALL));
		assertFalse(hidden.contains(ORE));
		assertTrue(sample(8, List.of(ore)).contains(ORE));
	}

	@Test void boundsDistanceAndRayCount() {
		assertFalse(sample(2, List.of(new Surface(ORE, new Box(ORE)))).contains(ORE));
		AtomicInteger calls = new AtomicInteger();
		assertTrue(VisibleSurfaceSampler.sample(EYE, 24, (start, end) -> {
			calls.incrementAndGet();
			assertEquals(24, start.distanceTo(end), 0.000001);
			return BlockHitResult.createMissed(end, Direction.UP, BlockPos.ofFloored(end));
		}).isEmpty());
		assertEquals(495, calls.get());
	}

	private static List<BlockPos> sample(double radius, List<Surface> surfaces) {
		return VisibleSurfaceSampler.sample(EYE, radius, (start, end) -> {
			BlockHitResult nearest = BlockHitResult.createMissed(end, Direction.UP, BlockPos.ofFloored(end));
			double distance = start.squaredDistanceTo(end);
			for (Surface surface : surfaces) {
				var hit = surface.box().raycast(start, end);
				if (hit.isPresent() && start.squaredDistanceTo(hit.get()) < distance) {
					distance = start.squaredDistanceTo(hit.get());
					nearest = new BlockHitResult(hit.get(), Direction.WEST, surface.pos(), false);
				}
			}
			return nearest;
		}).stream().map(BlockHitResult::getBlockPos).toList();
	}

	private record Surface(BlockPos pos, Box box) {}
}
