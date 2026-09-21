package ai.moeru.airicraft.agent.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RotationSpringTest {
	@Test
	void convergesWithoutSnappingOrOvershooting() {
		RotationSpring spring = new RotationSpring(new CameraController.Rotation(0, 0));
		CameraController.Rotation target = new CameraController.Rotation(90, 45);
		float previous = 0;
		for (int frame = 0; frame < 120; frame++) {
			CameraController.Rotation rotation = spring.advance(target, 1.0 / 60, 18);
			assertTrue(rotation.yaw() >= previous);
			assertTrue(rotation.yaw() <= 90);
			if (frame == 0) assertTrue(rotation.yaw() < 10);
			previous = rotation.yaw();
		}
		assertEquals(90, previous, 0.001);
	}

	@Test
	void usesShortestArcAcrossYawSeam() {
		RotationSpring spring = new RotationSpring(new CameraController.Rotation(179, 0));
		var rotation = spring.advance(new CameraController.Rotation(-179, 0), 0.05, 18);
		assertTrue(rotation.yaw() > 179 && rotation.yaw() < 181);
	}

	@Test
	void integrationIsIndependentOfFrameRateForFixedTarget() {
		RotationSpring slow = new RotationSpring(new CameraController.Rotation(0, 0));
		RotationSpring fast = new RotationSpring(new CameraController.Rotation(0, 0));
		var target = new CameraController.Rotation(150, -80);
		var expected = slow.advance(target, 0.1, 18);
		CameraController.Rotation actual = null;
		for (int i = 0; i < 12; i++) actual = fast.advance(target, 0.1 / 12, 18);
		assertEquals(expected.yaw(), actual.yaw(), 0.0001);
		assertEquals(expected.pitch(), actual.pitch(), 0.0001);
	}

	@Test
	void retargetingPreservesMomentum() {
		RotationSpring spring = new RotationSpring(new CameraController.Rotation(0, 0));
		var before = spring.advance(new CameraController.Rotation(90, 0), 0.05, 18);
		var after = spring.advance(new CameraController.Rotation(-90, 0), 0.001, 18);
		assertTrue(after.yaw() > before.yaw());
	}
}
