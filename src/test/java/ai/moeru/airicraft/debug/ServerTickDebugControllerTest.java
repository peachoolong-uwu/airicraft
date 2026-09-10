package ai.moeru.airicraft.debug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerTickDebugControllerTest {
	@Test
	void repeatingTheSamePausePreservesAuthorityAndDoesNotAdvanceEitherBoundary() {
		ServerTickDebugController controller = pausedController();
		var before = controller.status();
		controller.pause("debug-1");
		assertEquals(before, controller.status());
		assertFalse(controller.beginServerTick());
		assertThrows(ServerTickDebugController.DebugStateException.class, () -> controller.pause("different-session"));
		assertEquals(before, controller.status());
	}

	@Test
	void pausePermitsExactlyOneFinalServerTickThenRejectsFurtherTicks() {
		ServerTickDebugController controller = new ServerTickDebugController();

		controller.pause("debug-1");
		assertTrue(controller.beginServerTick());
		controller.completeServerTick();

		assertFalse(controller.beginServerTick());
		var status = controller.status();
		assertEquals(ServerTickDebugController.Phase.PAUSED, status.phase());
		assertEquals("debug-1", status.debugSessionId());
		assertEquals(1L, status.pauseEpoch());
		assertEquals(1L, status.serverTickId());
	}

	@Test
	void stepPermitsExactlyOneServerTickThenReturnsToTheSamePausedSession() {
		ServerTickDebugController controller = pausedController();

		controller.step("debug-1", 1L);
		assertTrue(controller.beginServerTick());
		controller.completeServerTick();

		assertFalse(controller.beginServerTick());
		var status = controller.status();
		assertEquals(ServerTickDebugController.Phase.PAUSED, status.phase());
		assertEquals("debug-1", status.debugSessionId());
		assertEquals(2L, status.pauseEpoch());
		assertEquals(2L, status.serverTickId());
	}

	@Test
	void continueRestoresNormalServerTicks() {
		ServerTickDebugController controller = pausedController();

		controller.continueRunning("debug-1", 1L);

		assertTrue(controller.beginServerTick());
		controller.completeServerTick();
		assertEquals(ServerTickDebugController.Phase.RUNNING, controller.status().phase());
		assertEquals(2L, controller.status().serverTickId());
	}

	private static ServerTickDebugController pausedController() {
		ServerTickDebugController controller = new ServerTickDebugController();
		controller.pause("debug-1");
		assertTrue(controller.beginServerTick());
		controller.completeServerTick();
		return controller;
	}
}
