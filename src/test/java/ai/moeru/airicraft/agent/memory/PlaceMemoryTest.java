package ai.moeru.airicraft.agent.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlaceMemoryTest {
	@TempDir Path directory;

	@Test
	void savesWithTheWorldAndSurvivesRecreationUpdateAndDelete() throws IOException {
		PlaceMemory memory = new PlaceMemory(directory);
		PlaceMemory.Place home = new PlaceMemory.Place("home", "minecraft:overworld", 1, 70, -3, "bed and supplies");
		memory.remember(home);
		assertEquals(home, new PlaceMemory(directory).recall("home").orElseThrow());
		assertTrue(Files.isRegularFile(directory.resolve("airicraft/places.json")));

		PlaceMemory.Place moved = new PlaceMemory.Place("home", "minecraft:the_nether", -5, 80, 12, "relocated");
		new PlaceMemory(directory).remember(moved);
		assertEquals(List.of(moved), memory.list());
		assertTrue(new PlaceMemory(directory).forget("home"));
		assertTrue(memory.list().isEmpty());
		assertFalse(memory.forget("home"));
	}

	@Test
	void preservedBoundsSurviveReloadAndOldBookmarksRemainCompatible() throws IOException {
		Path file = directory.resolve("airicraft/places.json");
		Files.createDirectories(file.getParent());
		Files.writeString(file, """
			{"version":1,"places":[{"name":"old","dimension":"minecraft:overworld","x":1,"y":64,"z":2,"note":""}]}
			""");
		PlaceMemory memory = new PlaceMemory(directory);
		assertNull(memory.recall("old").orElseThrow().preserveArea());
		var area = new PlaceMemory.PreservedArea(254, 62, 478, 258, 65, 482);
		memory.remember(new PlaceMemory.Place("home", "minecraft:overworld", 256, 63, 480, "shelter", area));
		assertEquals(area, new PlaceMemory(directory).recall("home").orElseThrow().preserveArea());
		assertTrue(area.contains(258, 62, 480), "door support belongs to the protected shell");
		assertFalse(area.contains(259, 62, 480));
		memory.remember(new PlaceMemory.Place("home", "minecraft:overworld", 256, 63, 480, "removed preservation"));
		assertNull(new PlaceMemory(directory).recall("home").orElseThrow().preserveArea());
	}

	@Test
	void identicalNamesDoNotLeakAcrossWorldSaves() throws IOException {
		PlaceMemory first = new PlaceMemory(directory.resolve("first"));
		PlaceMemory second = new PlaceMemory(directory.resolve("second"));
		first.remember(new PlaceMemory.Place("entrance", "minecraft:overworld", 1, 64, 1, ""));
		assertTrue(second.list().isEmpty());
		second.remember(new PlaceMemory.Place("entrance", "minecraft:overworld", 300, 80, 300, ""));
		assertEquals(1, first.recall("entrance").orElseThrow().x());
		assertEquals(300, second.recall("entrance").orElseThrow().x());
	}

	@Test
	void corruptOrUnsupportedDataIsReportedWithoutOverwritingIt() throws IOException {
		Path file = directory.resolve("airicraft/places.json");
		Files.createDirectories(file.getParent());
		for (String bad : List.of("{", "null", "{\"version\":2,\"places\":[]}",
			"{\"version\":1,\"places\":[null]}",
			"{\"version\":1,\"places\":[{\"name\":\"\",\"dimension\":\"minecraft:overworld\",\"x\":0,\"y\":0,\"z\":0,\"note\":\"\"}]}")) {
			Files.writeString(file, bad);
			assertThrows(IOException.class, () -> new PlaceMemory(directory).remember(
				new PlaceMemory.Place("home", "minecraft:overworld", 0, 64, 0, "")));
			assertEquals(bad, Files.readString(file));
		}
	}
}
