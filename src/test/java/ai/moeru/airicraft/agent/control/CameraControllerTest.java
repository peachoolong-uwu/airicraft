package ai.moeru.airicraft.agent.control;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraControllerTest {
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
