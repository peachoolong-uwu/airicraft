package ai.moeru.airicraft.agent.spatial;

import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/** Bounded first-hit observations. Sparse rays may miss visible surfaces. */
public final class VisibleSurfaceSampler {
	private VisibleSurfaceSampler() {}

	public static List<BlockHitResult> sample(Vec3d eye, double radius,
		BiFunction<Vec3d, Vec3d, BlockHitResult> raycast) {
		List<BlockHitResult> hits = new ArrayList<>();
		for (int yaw = 0; yaw < 360; yaw += 8) {
			for (int elevation = -75; elevation <= 75; elevation += 15) {
				double y = Math.toRadians(yaw), pitch = Math.toRadians(elevation);
				Vec3d direction = new Vec3d(Math.cos(y) * Math.cos(pitch), Math.sin(pitch), Math.sin(y) * Math.cos(pitch));
				BlockHitResult hit = raycast.apply(eye, eye.add(direction.multiply(radius)));
				if (hit.getType() == HitResult.Type.BLOCK) hits.add(hit);
			}
		}
		return List.copyOf(hits);
	}
}
