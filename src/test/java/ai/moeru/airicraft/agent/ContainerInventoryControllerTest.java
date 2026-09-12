package ai.moeru.airicraft.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ContainerInventoryControllerTest {
	private static ContainerInventoryController.Slot slot(int id, boolean chest, String components, int count) {
		return new ContainerInventoryController.Slot(id, chest, "minecraft:cobblestone", components, count, 64);
	}

	@Test void depositsExactQuantityAcrossStacksAndFillsPartialStacksFirst() {
		assertEquals(List.of(new ContainerInventoryController.Move(27, 1, 4),
			new ContainerInventoryController.Move(27, 0, 60), new ContainerInventoryController.Move(28, 0, 1)),
			ContainerInventoryController.plan(List.of(slot(0, true, "", 0), slot(1, true, "", 60),
				slot(27, false, "", 64), slot(28, false, "", 12)), "deposit", "minecraft:cobblestone", 65));
	}

	@Test void withdrawsIntoCarriedSlotsWithoutMixingComponentVariants() {
		assertEquals(List.of(new ContainerInventoryController.Move(0, 28, 7)),
			ContainerInventoryController.plan(List.of(slot(0, true, "named", 12), slot(27, false, "plain", 20),
				slot(28, false, "", 0)), "withdraw", "minecraft:cobblestone", 7));
	}

	@Test void refusesWholeRequestBeforeAnyClicksWhenSpaceOrSourceIsInsufficient() {
		assertThrows(IllegalStateException.class, () -> ContainerInventoryController.plan(
			List.of(slot(0, true, "", 63), slot(27, false, "", 64)), "deposit", "minecraft:cobblestone", 2));
		assertThrows(IllegalStateException.class, () -> ContainerInventoryController.plan(
			List.of(slot(0, true, "", 0), slot(27, false, "", 1)), "deposit", "minecraft:cobblestone", 2));
	}

	@Test void incompatibleVariantsCannotBothReserveTheSameEmptySlot() {
		assertThrows(IllegalStateException.class, () -> ContainerInventoryController.plan(List.of(
			slot(0, true, "", 0), slot(27, false, "one", 2), slot(28, false, "two", 2)),
			"deposit", "minecraft:cobblestone", 4));
	}
}
