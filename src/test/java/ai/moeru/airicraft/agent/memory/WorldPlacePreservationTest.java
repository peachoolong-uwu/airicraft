package ai.moeru.airicraft.agent.memory;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorldPlacePreservationTest {
	@Test
	void snapshotScopesRegionsToWorldIdentityAndDimension() {
		Object world = new Object();
		var home = new PlaceMemory.Place("home", "minecraft:overworld", 256, 63, 480, "",
			new PlaceMemory.PreservedArea(254, 62, 478, 258, 65, 482));
		var bookmark = new PlaceMemory.Place("mine", "minecraft:overworld", 280, 63, 480, "");
		var snapshot = WorldPlacePreservation.Snapshot.from(world, "minecraft:overworld", List.of(home, bookmark));
		assertTrue(snapshot.contains(world, 258, 62, 480));
		assertFalse(snapshot.contains(world, 280, 63, 480));
		assertFalse(snapshot.contains(new Object(), 258, 62, 480));
		assertFalse(WorldPlacePreservation.Snapshot.from(world, "minecraft:the_nether", List.of(home)).contains(world, 258, 62, 480));
		assertFalse(WorldPlacePreservation.Snapshot.from(world, "minecraft:overworld", List.of()).contains(world, 258, 62, 480));
	}

	@Test
	void unavailableMemoryRestrictsOnlyTheAffectedWorld() {
		Object world = new Object();
		var snapshot = new WorldPlacePreservation.Snapshot(world, List.of(), true);
		assertTrue(snapshot.contains(world, 0, 0, 0));
		assertFalse(snapshot.contains(new Object(), 0, 0, 0));
	}
}
