package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmeltingTaskExecutorTest {
	@Test void reservesOnlyInputsStillNeededFromInventory() {
		assertEquals(0, SmeltingTaskExecutor.fuelCountAfterReservingInput("log", 1, "log", 1));
		assertEquals(4, SmeltingTaskExecutor.fuelCountAfterReservingInput("log", 10, "log", 6));
		assertEquals(12, SmeltingTaskExecutor.fuelCountAfterReservingInput("planks", 12, "log", 6));
		int reserved = SmeltingTaskExecutor.remainingItemsToMove("log", 2, "log", 3);
		assertEquals(1, SmeltingTaskExecutor.fuelCountAfterReservingInput("log", 2, "log", reserved));
	}

	@Test void confirmedOpenFurnaceRequiresTheSameScreenAndDimension() {
		var key = new SmeltingStationKey("minecraft:overworld#open_screen", 4, 0, 0);
		assertEquals(true, SmeltingTaskExecutor.canCollectFromCurrentScreen(key, "minecraft:overworld", 4, true));
		assertEquals(false, SmeltingTaskExecutor.canCollectFromCurrentScreen(key, "minecraft:overworld", 5, true));
		assertEquals(false, SmeltingTaskExecutor.canCollectFromCurrentScreen(key, "minecraft:the_nether", 4, true));
		assertEquals(false, SmeltingTaskExecutor.canCollectFromCurrentScreen(key, "minecraft:overworld", 4, false));
		assertEquals(false, SmeltingTaskExecutor.canCollectFromCurrentScreen(null, "minecraft:overworld", 4, true));
		assertEquals(false, SmeltingTaskExecutor.canCollectFromCurrentScreen(new SmeltingStationKey("minecraft:overworld", 4, 0, 0), "minecraft:overworld", 4, true));
	}

	@Test
	void fuelItemsNeededRoundsUpCookTime() {
		assertEquals(0, SmeltingTaskExecutor.fuelItemsNeeded(0, 1600));
		assertEquals(1, SmeltingTaskExecutor.fuelItemsNeeded(200, 1600));
		assertEquals(2, SmeltingTaskExecutor.fuelItemsNeeded(1800, 1600));
	}

	@Test
	void fuelQuantityMustCoverFullCookTime() {
		assertEquals(false, SmeltingTaskExecutor.fuelQuantityCoversCookTime(1, 200, 100, 1));
		assertEquals(true, SmeltingTaskExecutor.fuelQuantityCoversCookTime(1, 200, 100, 2));
		assertEquals(true, SmeltingTaskExecutor.fuelQuantityCoversCookTime(3, 200, 1600, 1));
	}

	@Test
	void remainingItemsToMoveCountsMatchingTargetSlotContents() {
		assertEquals(3, SmeltingTaskExecutor.remainingItemsToMove(null, 0, "minecraft:raw_iron", 3));
		assertEquals(1, SmeltingTaskExecutor.remainingItemsToMove("minecraft:raw_iron", 2, "minecraft:raw_iron", 3));
		assertEquals(0, SmeltingTaskExecutor.remainingItemsToMove("minecraft:raw_iron", 3, "minecraft:raw_iron", 3));
		assertEquals(-1, SmeltingTaskExecutor.remainingItemsToMove("minecraft:coal", 1, "minecraft:raw_iron", 3));
	}
}
