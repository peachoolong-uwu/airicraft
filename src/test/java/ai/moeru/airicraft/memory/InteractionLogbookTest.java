package ai.moeru.airicraft.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class InteractionLogbookTest {
	@TempDir Path directory;
	private InteractionLogbook.Entry entry(long tick, String action, String item, int count) {
		return new InteractionLogbook.Entry(tick * 50, tick, "actor", "minecraft:overworld", 1, 64, 2,
			action, item, count, "minecraft:chest", Map.of());
	}
	@Test void persistsAcrossReadersAndKeepsLatestMatchingEntriesWithoutMixingWorlds() throws Exception {
		var first = entry(1, "crafted", "minecraft:chest", 1);
		var second = entry(2, "container_put", "minecraft:stone", 32);
		var third = entry(3, "container_take", "minecraft:stone", 7);
		InteractionLogbook.append(directory, List.of(first, second));
		InteractionLogbook.append(directory, List.of(third));
		assertEquals(List.of(second, third), InteractionLogbook.read(directory, e -> e.itemId().equals("minecraft:stone"), 20));
		assertEquals(List.of(third), InteractionLogbook.read(directory, e -> true, 1));
		assertEquals(List.of(), InteractionLogbook.read(directory.resolve("other-world"), e -> true, 20));
	}
	@Test void queryWaitsForQueuedWritesAndSnapshotsRemainImmutable() {
		var stock = new java.util.HashMap<>(Map.of("minecraft:iron_ingot", 12));
		var observation = new InteractionLogbook.Entry(1, 2, "actor", "minecraft:overworld", 1, 64, 2,
			"container_observed", "", 0, "minecraft:chest", stock);
		stock.clear();
		InteractionLogbook.record(directory, List.of(observation));
		var entries = InteractionLogbook.query(directory, e -> e.contents().containsKey("minecraft:iron_ingot"), 20).join();
		assertEquals(12, entries.getFirst().contents().get("minecraft:iron_ingot"));
		assertEquals(2, entries.getFirst().worldTick());
	}
	@Test void itemQueryKeepsLaterEmptyStockObservations() throws Exception {
		var stocked = new InteractionLogbook.Entry(1, 1, "actor", "minecraft:overworld", 1, 64, 2,
			"container_observed", "", 0, "minecraft:chest", Map.of("minecraft:iron_ingot", 12));
		var empty = new InteractionLogbook.Entry(2, 2, "actor", "minecraft:overworld", 1, 64, 2,
			"container_observed", "", 0, "minecraft:chest", Map.of());
		InteractionLogbook.append(directory, List.of(stocked, empty));
		assertEquals(List.of(empty), InteractionLogbook.read(directory,
			InteractionLogbook.matchingItemHistory("minecraft:iron_ingot"), 1));
	}
}
