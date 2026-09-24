package ai.moeru.airicraft.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ContainerPolicyHostTest {
	@Test void confirmationRequiresBothSourceAndDestinationCounts() {
		var before = new ContainerPolicyHost.View(7, Map.of("minecraft:bread", 20), Map.of("minecraft:bread", 3));
		var expected = ContainerPolicyHost.afterWithdrawal(before, List.of(new ContainerInventoryController.TransferItem("minecraft:bread", 5)));
		assertEquals(new ContainerPolicyHost.View(7, Map.of("minecraft:bread", 15), Map.of("minecraft:bread", 8)), expected);
		assertNotEquals(new ContainerPolicyHost.View(7, Map.of("minecraft:bread", 20), Map.of("minecraft:bread", 8)), expected);
		assertNotEquals(before, expected);
	}

	@Test void duplicateItemEntriesCannotOverdrawStock() {
		var before = new ContainerPolicyHost.View(1, Map.of("minecraft:bread", 5), Map.of());
		assertThrows(IllegalStateException.class, () -> ContainerPolicyHost.afterWithdrawal(before, List.of(
			new ContainerInventoryController.TransferItem("minecraft:bread", 3), new ContainerInventoryController.TransferItem("minecraft:bread", 3))));
	}
}
