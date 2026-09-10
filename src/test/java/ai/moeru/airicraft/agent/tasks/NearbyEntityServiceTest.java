package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NearbyEntityServiceTest {
	@Test void filtersBeforeLimitingAndKeepsNearestMatch() {
		var snapshots = List.of(entity(1, "minecraft:pig", 1), entity(2, "minecraft:sheep", 80), entity(3, "minecraft:sheep", 40));
		assertEquals(List.of(3), NearbyEntityService.selectSnapshots(snapshots, Set.of("minecraft:sheep"), 1).stream()
			.map(NearbyEntityService.NearbyEntitySnapshot::entityId).toList());
		assertEquals(List.of(1, 3), NearbyEntityService.selectSnapshots(snapshots, Set.of(), 2).stream()
			.map(NearbyEntityService.NearbyEntitySnapshot::entityId).toList());
	}

	private static NearbyEntityService.NearbyEntitySnapshot entity(int id, String type, double distance) {
		return new NearbyEntityService.NearbyEntitySnapshot(id, "uuid-" + id, type, type, distance, 64, 0, distance, true, 10F, 10F, false);
	}
}
