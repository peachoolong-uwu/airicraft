package ai.moeru.airicraft.debug;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTickDebugControllerTest {
	@Test
	void serverPauseAndStepPermitExactlyOneClientDecisionTickBeforeCapture() {
		ClientTickDebugController client = new ClientTickDebugController();
		ServerTickDebugController server = new ServerTickDebugController();
		assertTrue(client.beginServerAlignedTick(server.status()));
		client.onClientTickCompleted();
		var pause = client.pause();
		String session = client.status().debugSessionId();
		server.pause(session);
		assertFalse(client.beginServerAlignedTick(server.status()));
		assertTrue(server.beginServerTick());
		assertFalse(client.beginServerAlignedTick(server.status()));
		server.completeServerTick();
		assertTrue(client.beginServerAlignedTick(server.status()));
		var intent = client.onClientTickCompleted().orElseThrow();
		assertFalse(client.beginServerAlignedTick(server.status()));
		client.attachSnapshot(intent, snapshot(intent));
		client.completeFrame(intent, frame());
		pause.join();
		long tick = client.status().clientTickId();
		for (int i = 0; i < 100; i++) {
			assertFalse(client.beginServerAlignedTick(server.status()));
		}
		assertEquals(tick, client.status().clientTickId());

		var step = client.step(session, 1L);
		server.step(session, 1L);
		assertFalse(client.beginServerAlignedTick(server.status()));
		server.beginServerTick();
		assertFalse(client.beginServerAlignedTick(server.status()));
		server.completeServerTick();
		assertTrue(client.beginServerAlignedTick(server.status()));
		var stepIntent = client.onClientTickCompleted().orElseThrow();
		assertFalse(client.beginServerAlignedTick(server.status()));
		client.attachSnapshot(stepIntent, snapshot(stepIntent));
		client.completeFrame(stepIntent, frame());
		assertEquals(tick + 1L, step.join().snapshot().clientTickId());
		assertEquals(2L, server.status().serverTickId());
		assertFalse(client.beginServerAlignedTick(server.status()));
		server.continueRunning(session, 2L);
		client.continueRunning(session, 2L);
		assertTrue(client.beginServerAlignedTick(server.status()));
	}

	@Test
	void pauseCapturesCurrentRenderedFrameWithoutAdvancingATick() {
		ClientTickDebugController controller = new ClientTickDebugController();
		var future = controller.pause();

		assertFalse(controller.allowVanillaTick(true));
		var intent = controller.onRenderedFrameBoundary().orElseThrow();
		controller.attachSnapshot(intent, snapshot(intent));
		controller.completeFrame(intent, frame());

		var capture = future.join();
		assertEquals(0L, capture.snapshot().clientTickId());
		assertEquals("00000000-0000-0000-0000-000000000007", capture.snapshot().player().uuid());
		assertEquals(20, capture.snapshot().player().hunger().food());
		assertEquals(1L, capture.pauseEpoch());
		assertEquals("CAPTURED", capture.frame().status());
		assertTrue(controller.status().paused());
	}

	@Test
	void stepAllowsOneClientTickThenWaitsForItsRenderedFrame() {
		ClientTickDebugController controller = pausedController();
		var initial = controller.status();
		var future = controller.step(initial.debugSessionId(), initial.pauseEpoch());

		assertTrue(controller.allowVanillaTick(true));
		controller.onClientTickStarted();
		assertTrue(controller.allowVanillaTick(true));
		var intent = controller.onClientTickCompleted().orElseThrow();
		assertFalse(controller.allowVanillaTick(true));
		controller.attachSnapshot(intent, snapshot(intent));
		controller.completeFrame(intent, frame());

		var capture = future.join();
		assertEquals(1L, capture.snapshot().clientTickId());
		assertEquals(2L, capture.pauseEpoch());
		assertFalse(controller.allowVanillaTick(true));
	}

	@Test
	void pauseUsesAnAlreadyScheduledClientTickAsTheCapturedBoundary() {
		ClientTickDebugController controller = new ClientTickDebugController();
		var future = controller.pause();

		controller.onClientTickStarted();
		assertTrue(controller.allowVanillaTick(true));
		var intent = controller.onClientTickCompleted().orElseThrow();
		controller.attachSnapshot(intent, snapshot(intent));
		controller.completeFrame(intent, frame());

		assertEquals(1L, future.join().snapshot().clientTickId());
	}

	@Test
	void playerActionCaptureStaysEnabledForThePauseSession() {
		ClientTickDebugController controller = new ClientTickDebugController();
		var future = controller.pause(true);
		assertTrue(controller.capturesPlayerActions());
		var intent = controller.onRenderedFrameBoundary().orElseThrow();
		controller.attachSnapshot(intent, snapshot(intent));
		controller.completeFrame(intent, frame());
		future.join();

		var status = controller.status();
		var step = controller.step(status.debugSessionId(), status.pauseEpoch());
		controller.onClientTickStarted();
		var stepIntent = controller.onClientTickCompleted().orElseThrow();
		controller.attachSnapshot(stepIntent, snapshot(stepIntent));
		controller.completeFrame(stepIntent, frame());
		step.join();
		assertTrue(controller.capturesPlayerActions());

		var steppedStatus = controller.status();
		controller.continueRunning(steppedStatus.debugSessionId(), steppedStatus.pauseEpoch());
		assertFalse(controller.capturesPlayerActions());
	}

	@Test
	void staleSessionAndEpochCannotAdvanceTheClient() {
		ClientTickDebugController controller = pausedController();
		var status = controller.status();

		var staleSession = assertThrows(
			ClientTickDebugController.DebugStateException.class,
			() -> controller.step("stale", status.pauseEpoch())
		);
		assertEquals("stale_debug_session", staleSession.code());

		var staleEpoch = assertThrows(
			ClientTickDebugController.DebugStateException.class,
			() -> controller.step(status.debugSessionId(), status.pauseEpoch() - 1L)
		);
		assertEquals("stale_pause_epoch", staleEpoch.code());
	}

	@Test
	void advancingInvalidatesThePreviousWorldSnapshotHandle() {
		ClientTickDebugController controller = pausedController();
		var status = controller.status();
		controller.requireCurrentSnapshot(status.snapshotId());

		controller.step(status.debugSessionId(), status.pauseEpoch());

		var exception = assertThrows(
			ClientTickDebugController.DebugStateException.class,
			() -> controller.requireCurrentSnapshot(status.snapshotId())
		);
		assertEquals("snapshot_not_paused", exception.code());
	}

	@Test
	void continueRequiresCurrentAuthorityAndReturnsToNormalTicks() {
		ClientTickDebugController controller = pausedController();
		var status = controller.status();

		controller.continueRunning(status.debugSessionId(), status.pauseEpoch());

		assertEquals(ClientTickDebugController.Phase.RUNNING, controller.status().phase());
		assertTrue(controller.allowVanillaTick(true));
	}

	private static ClientTickDebugController pausedController() {
		ClientTickDebugController controller = new ClientTickDebugController();
		var future = controller.pause();
		var intent = controller.onRenderedFrameBoundary().orElseThrow();
		controller.attachSnapshot(intent, snapshot(intent));
		controller.completeFrame(intent, frame());
		future.join();
		return controller;
	}

	private static ClientTickDebugController.ClientTickSnapshot snapshot(
		ClientTickDebugController.CaptureIntent intent
	) {
		return new ClientTickDebugController.ClientTickSnapshot(
			2,
			intent.debugSessionId(),
			intent.captureId(),
			intent.snapshotId(),
			intent.clientTickId(),
			100L,
			"minecraft:overworld",
			200L,
			300L,
			playerSnapshot(),
			null,
			4L,
			"IDLE"
		);
	}

	private static ClientTickPlayerSnapshot playerSnapshot() {
		return new ClientTickPlayerSnapshot(
			7,
			"00000000-0000-0000-0000-000000000007",
			"Player",
			"minecraft:player",
			"survival",
			new ClientTickPlayerSnapshot.PositionSnapshot(1.5D, 64.0D, 2.5D, 1, 64, 2),
			new ClientTickPlayerSnapshot.RotationSnapshot(10.0F, 20.0F, 10.0F, 10.0F),
			new ClientTickPlayerSnapshot.VectorSnapshot(0.0D, 0.0D, 0.0D),
			new ClientTickPlayerSnapshot.BoundsSnapshot(1.2D, 64.0D, 2.2D, 1.8D, 65.8D, 2.8D),
			new ClientTickPlayerSnapshot.MovementSnapshot(
				"standing", true, false, false, false, false, false, false, false,
				false, false, false, false, 0.0F, false, 0, 0
			),
			new ClientTickPlayerSnapshot.VitalsSnapshot(
				true, false, 10.0F, 20.0F, 0.0F, 0, 300, 300, 0, 0, 0, 0
			),
			new ClientTickPlayerSnapshot.HungerSnapshot(20, 5.0F),
			new ClientTickPlayerSnapshot.ExperienceSnapshot(0, 0, 0.0F),
			new ClientTickPlayerSnapshot.AbilitiesSnapshot(
				false, false, false, false, true, 0.05F, 0.1F
			),
			new ClientTickPlayerSnapshot.InputSnapshot(false, false, false, false, false, false, false),
			41,
			0,
			List.of(),
			Map.of(),
			List.of(),
			List.of()
		);
	}

	private static ClientTickDebugController.ClientTickFrame frame() {
		return ClientTickDebugController.ClientTickFrame.captured("png", 854, 480, 1920, 1080, 200L, new byte[]{1, 2, 3});
	}
}
