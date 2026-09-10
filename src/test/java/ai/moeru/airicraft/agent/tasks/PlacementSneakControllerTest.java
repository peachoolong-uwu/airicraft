package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlacementSneakControllerTest {
	@Test
	void pressesSneakAndWaitsBeforePlacementWhenPlayerIsStanding() {
		assertEquals(
			PlacementSneakController.Preparation.PRESS_AND_WAIT,
			PlacementSneakController.preparation(false, false, false)
		);
	}

	@Test
	void waitsForSneakInputToReachPlayerBeforePlacement() {
		assertEquals(
			PlacementSneakController.Preparation.WAITING,
			PlacementSneakController.preparation(true, true, false)
		);
		assertEquals(
			PlacementSneakController.Preparation.WAITING,
			PlacementSneakController.preparation(false, true, false)
		);
	}

	@Test
	void restoresOwnedSneakInputClearedDuringNavigationRelease() {
		assertEquals(
			PlacementSneakController.Preparation.PRESS_AND_WAIT,
			PlacementSneakController.preparation(true, false, false)
		);
		// The player flag can still reflect the preceding tick after the key was cleared.
		assertEquals(
			PlacementSneakController.Preparation.PRESS_AND_WAIT,
			PlacementSneakController.preparation(true, false, true)
		);
	}

	@Test
	void placementIsReadyOnlyAfterPlayerIsSneaking() {
		assertEquals(
			PlacementSneakController.Preparation.READY,
			PlacementSneakController.preparation(true, true, true)
		);
		assertEquals(
			PlacementSneakController.Preparation.READY,
			PlacementSneakController.preparation(false, true, true)
		);
	}
}
