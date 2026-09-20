package ai.moeru.airicraft.agent.control;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraControllerTest {
	@Test void blockHitAllowsOffCenterAimButRejectsMissOrOccluder() {
		var pos = new net.minecraft.util.math.BlockPos(0, 0, 3);
		var eye = new Vec3d(0.5, 0.5, 0);
		var offCenter = net.minecraft.util.shape.VoxelShapes.fullCube().raycast(eye, new Vec3d(0.85, 0.8, 4), pos);
		assertTrue(CameraController.blockHit(offCenter, pos).isPresent(), "Cursor already intersects target before center alignment");
		assertTrue(CameraController.blockHit(offCenter, pos.east()).isEmpty(), "Another block is not the intended target");
		var miss = net.minecraft.util.hit.BlockHitResult.createMissed(new Vec3d(0.5, 0.5, 3.5), net.minecraft.util.math.Direction.NORTH, pos);
		assertTrue(CameraController.blockHit(miss, pos).isEmpty(), "A MISS can carry the same block coordinates");
	}

	@Test void thinBlockAimFallsInsideOutlineRatherThanBlockCenter() {
		var pos = new net.minecraft.util.math.BlockPos(-229, 70, -2);
		var shape = net.minecraft.util.shape.VoxelShapes.cuboid(0, 0, 0, 0.5, 0.0625, 1);
		var eye = new Vec3d(-228, 71.62, -1.5);
		var aim = CameraController.blockAim(pos, shape, eye).orElseThrow();
		assertTrue(shape.raycast(eye, aim, pos) != null);
		assertTrue(aim.y < 70.0625);
	}

	@Test void springCrossesBlockOutlineBeforeItSettles() {
		var controller = new CameraController();
		var eye = new Vec3d(0.5, 0.5, 0);
		var pos = new net.minecraft.util.math.BlockPos(0, 0, 3);
		controller.startMotion(new CameraController.Rotation(-70, 0), new CameraController.Rotation(0, 0), 0, "baritone");
		boolean hitBeforeSettled = false;
		for (int i = 0; i < 30 && controller.activeReason().isPresent(); i++) {
			var rotation = controller.tickMotion().orElseThrow();
			var end = eye.add(Vec3d.fromPolar(rotation.pitch(), rotation.yaw()).multiply(4.5));
			var hit = net.minecraft.util.shape.VoxelShapes.fullCube().raycast(eye, end, pos);
			if (hit != null && CameraController.blockHit(hit, pos).isPresent()) {
				hitBeforeSettled = controller.activeReason().isPresent() && Math.abs(rotation.yaw()) > 0.5;
				break;
			}
		}
		assertTrue(hitBeforeSettled, "Mining should not wait for the old 0.5-degree gate or spring completion");
	}

	@Test
	void lookRotationComputesYawAndClampsPitch() {
		CameraController.Rotation east = CameraController.lookRotation(Vec3d.ZERO, new Vec3d(1.0D, 0.0D, 0.0D)).orElseThrow();
		assertEquals(-90.0F, east.yaw(), 0.001F);
		assertEquals(0.0F, east.pitch(), 0.001F);

		CameraController.Rotation vertical = CameraController.lookRotation(Vec3d.ZERO, new Vec3d(0.0D, 10.0D, 0.0D)).orElseThrow();
		assertEquals(-90.0F, vertical.pitch(), 0.001F);
		assertTrue(CameraController.lookRotation(Vec3d.ZERO, Vec3d.ZERO).isEmpty());
	}

	@Test
	void springAdvancesAcrossYawSeamAndReleasesAtTarget() {
		CameraController controller = new CameraController();
		controller.startMotion(new CameraController.Rotation(170, 0),
			new CameraController.Rotation(-170, 40), 0, "test");
		var first = controller.tickMotion().orElseThrow();
		assertTrue(first.yaw() > 170 && first.yaw() < 190);
		assertTrue(first.pitch() > 0 && first.pitch() < 40);
		assertTrue(controller.activeReason().isPresent());
		CameraController.Rotation last = first;
		for (int i = 0; i < 40 && controller.activeReason().isPresent(); i++) {
			last = controller.tickMotion().orElseThrow();
		}
		assertEquals(190, last.yaw(), 0.1);
		assertEquals(40, last.pitch(), 0.1);
		assertTrue(controller.activeReason().isEmpty());
	}

	@Test
	void repeatedTargetsDoNotRestartTheSpring() {
		CameraController controller = new CameraController();
		var current = new CameraController.Rotation(0, 0);
		var target = new CameraController.Rotation(90, 0);
		for (int i = 0; i < 20; i++) {
			controller.startMotion(current, target, 0, "tracking");
			current = controller.tickMotion().orElseThrow();
		}
		assertEquals(90, current.yaw(), 0.1);
	}

	@Test
	void directLookOwnsCameraUntilSettledButBaritoneCanRetargetItsOwnMotion() {
		CameraController controller = new CameraController();
		var start = new CameraController.Rotation(0, 0);
		var target = new CameraController.Rotation(90, 0);
		controller.startMotion(start, target, 0, "baritone");
		assertTrue(controller.acceptsBaritoneTarget());
		controller.startMotion(start, target, 0, "player_look_at");
		assertTrue(!controller.acceptsBaritoneTarget());
		for (int i = 0; i < 40; i++) controller.tickMotion();
		assertTrue(controller.acceptsBaritoneTarget());
	}

	@Test
	void clearingCancelsCaptureWaitAndReleasesOwnership() {
		CameraController controller = new CameraController();
		controller.startMotion(new CameraController.Rotation(0, 0),
			new CameraController.Rotation(90, 0), 0, "vision");
		var pending = controller.whenAligned();
		assertTrue(controller.capturePending());
		controller.clear();
		assertTrue(pending.isCompletedExceptionally());
		assertTrue(!controller.capturePending());
		assertTrue(controller.acceptsBaritoneTarget());
	}

	@Test
	void clearCancelsPendingMotion() {
		CameraController controller = new CameraController();
		controller.startMotion(
			new CameraController.Rotation(0.0F, 0.0F),
			new CameraController.Rotation(90.0F, 30.0F),
			3,
			"test"
		);

		controller.clear();

		assertTrue(controller.activeReason().isEmpty());
		assertEquals(Optional.empty(), controller.tickMotion());
	}

}
