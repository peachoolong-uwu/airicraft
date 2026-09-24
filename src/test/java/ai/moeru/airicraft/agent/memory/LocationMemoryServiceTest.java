package ai.moeru.airicraft.agent.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocationMemoryServiceTest {
	@TempDir Path directory;

	@Test
	void fileBackendKeepsIdsAcrossRenameRestartAndDimensionChanges() throws Exception {
		var service = new LocationMemoryService(new PlaceMemory(directory));
		var area = new PlaceMemory.PreservedArea(0, 60, 0, 5, 70, 5);
		var home = service.remember(null, new PlaceMemory.Place("home", "minecraft:overworld", 2, 64, 2, "shelter", area));
		var reopened = new LocationMemoryService(new PlaceMemory(directory));
		assertEquals(home, reopened.recall(home.id(), null));
		assertEquals(home, reopened.recall(null, "home"));
		var renamed = reopened.remember(home.id(), new PlaceMemory.Place("base", "minecraft:the_nether", 4, 80, 9, "moved"));
		assertEquals(home.id(), renamed.id());
		assertNull(renamed.preserveArea());
		assertEquals(List.of(renamed), new LocationMemoryService(new PlaceMemory(directory)).list());
		assertThrows(IllegalArgumentException.class, () -> service.recall(null, "home"));
		assertTrue(service.forget(home.id(), null));
		assertFalse(service.forget(home.id(), null));
		assertTrue(reopened.list().isEmpty());
	}

	@Test
	void legacyReadDoesNotRewriteAndFirstUpdateRetainsItsId() throws Exception {
		Path file = directory.resolve("airicraft/places.json");
		Files.createDirectories(file.getParent());
		String old = "{\"version\":1,\"places\":[{\"name\":\"old\",\"dimension\":\"minecraft:overworld\",\"x\":1,\"y\":64,\"z\":2,\"note\":\"legacy\"}]}";
		Files.writeString(file, old);
		var service = new LocationMemoryService(new PlaceMemory(directory));
		String id = service.recall(null, "old").id();
		assertEquals(old, Files.readString(file));
		service.remember(id, new PlaceMemory.Place("new", "minecraft:overworld", 3, 64, 2, "edited"));
		assertEquals(id, new LocationMemoryService(new PlaceMemory(directory)).recall(null, "new").id());
	}

	@Test
	void installedButMissingOrLoadingJourneyMapNeverTouchesFallback() {
		java.util.function.Supplier<LocationMemoryProvider> local = () -> { fail("fallback must not be accessed"); return null; };
		assertThrows(IllegalStateException.class, () -> LocationMemoryBridge.select(true, null, local));
		var external = new MemoryProvider();
		external.ready = false;
		assertThrows(IllegalStateException.class, () -> LocationMemoryBridge.select(true, external, local));
		external.ready = true;
		assertSame(external, LocationMemoryBridge.select(true, external, local));
		var fallback = new PlaceMemory(directory);
		assertSame(fallback, LocationMemoryBridge.select(false, external, () -> fallback));
	}

	@Test
	void selectedExternalStoreStartsFreshAndLeavesLegacyBytesUntouched() throws Exception {
		var local = new PlaceMemory(directory);
		local.remember(new PlaceMemory.Place("old", "minecraft:overworld", 1, 64, 2, "legacy"));
		Path file = directory.resolve("airicraft/places.json");
		String before = Files.readString(file);
		var external = new MemoryProvider();
		var service = new LocationMemoryService(LocationMemoryBridge.select(true, external, () -> local));
		assertTrue(service.list().isEmpty());
		service.remember(null, new PlaceMemory.Place("new", "minecraft:overworld", 3, 64, 2, ""));
		assertEquals(before, Files.readString(file));
		assertEquals(List.of("old"), new LocationMemoryService(LocationMemoryBridge.select(false, external, () -> local))
			.list().stream().map(LocationMemoryProvider.Location::name).toList());
	}

	@Test
	void duplicateNamesRequireIdsAndMissingUpdateIdNeverCreatesAPlace() throws Exception {
		var backend = new MemoryProvider();
		backend.values.add(LocationMemoryProvider.Location.from("one", new PlaceMemory.Place("home", "minecraft:overworld", 0, 64, 0, "")));
		backend.values.add(LocationMemoryProvider.Location.from("two", new PlaceMemory.Place("home", "minecraft:the_nether", 1, 64, 0, "")));
		var service = new LocationMemoryService(backend);
		assertEquals("two", service.recall("two", null).id());
		for (org.junit.jupiter.api.function.Executable action : List.<org.junit.jupiter.api.function.Executable>of(
			() -> service.recall(null, "home"), () -> service.forget(null, "home"),
			() -> service.remember(null, new PlaceMemory.Place("home", "minecraft:overworld", 9, 64, 9, "")))) {
			String message = assertThrows(IllegalArgumentException.class, action).getMessage();
			assertTrue(message.contains("ambiguous_place"));
			assertTrue(message.contains("one") && message.contains("two"));
		}
		assertThrows(IllegalArgumentException.class, () -> service.remember("missing", new PlaceMemory.Place("new", "minecraft:overworld", 0, 64, 0, "")));
		assertEquals(2, service.list().size());
		assertTrue(service.forget("two", null));
		assertEquals("one", service.recall(null, "home").id());
	}

	private static final class MemoryProvider implements LocationMemoryProvider {
		boolean ready = true;
		final List<Location> values = new ArrayList<>();
		public String id() { return "test"; }
		public boolean available() { return ready; }
		public List<Location> listLocations() { return List.copyOf(values); }
		public Location save(String id, PlaceMemory.Place place) {
			String target = id == null ? java.util.UUID.randomUUID().toString() : id;
			values.removeIf(value -> value.id().equals(target));
			var location = Location.from(target, place);
			values.add(location);
			return location;
		}
		public boolean delete(String id) { return values.removeIf(value -> value.id().equals(id)); }
	}
}
