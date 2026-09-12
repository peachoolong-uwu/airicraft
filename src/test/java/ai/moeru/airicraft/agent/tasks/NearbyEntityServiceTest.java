package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NearbyEntityServiceTest {
	@Test void filtersBeforeLimitingAndKeepsNearestMatch() {
		var snapshots = List.of(entity(1, "minecraft:pig", 1), entity(2, "minecraft:sheep", 80), entity(3, "minecraft:sheep", 40));
		assertEquals(List.of(3), NearbyEntityService.selectSnapshots(snapshots, Set.of("minecraft:sheep"), 1).stream()
			.map(NearbyEntityService.NearbyEntitySnapshot::entityId).toList());
		assertEquals(List.of(1, 3), NearbyEntityService.selectSnapshots(snapshots, Set.of(), 2).stream()
			.map(NearbyEntityService.NearbyEntitySnapshot::entityId).toList());
	}

	@Test void reportsVisibleAgeWithoutInventingAgeForNonlivingEntities() {
		var chick = new NearbyEntityService.NearbyEntitySnapshot(1, "chick", "Chicken", "minecraft:chicken", 0, 64, 0, 1, true, 4F, 4F, true, false);
		var adult = entity(2, "minecraft:chicken", 2);
		var item = new NearbyEntityService.NearbyEntitySnapshot(3, "item", "Egg", "minecraft:item", 0, 64, 0, 3, true, null, null, null, false);
		assertTrue(chick.compactDescription().contains("baby=true"));
		assertTrue(adult.compactDescription().contains("baby=false"));
		assertFalse(item.compactDescription().contains("baby="));
	}

	private static NearbyEntityService.NearbyEntitySnapshot entity(int id, String type, double distance) {
		return new NearbyEntityService.NearbyEntitySnapshot(id, "uuid-" + id, type, type, distance, 64, 0, distance, true, 10F, 10F, false, false);
	}
}
