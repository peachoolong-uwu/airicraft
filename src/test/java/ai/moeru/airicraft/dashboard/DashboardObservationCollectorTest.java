package ai.moeru.airicraft.dashboard;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.List;
import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;
import ai.moeru.airicraft.agent.tasks.WorldEvidence;
import ai.moeru.airicraft.agent.tasks.MissionExecutionSnapshot;
import com.google.gson.Gson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardObservationCollectorTest {
	@Test
	void missionSnapshotsReferenceOneCatalogAndKeepCurrentEvidence() {
		DashboardObservationStore store = new DashboardObservationStore(1024L * 1024L);
		var recipe = new CraftingOpportunity("bread", "minecraft:bread", 1, List.of("minecraft:wheat", "minecraft:wheat", "minecraft:wheat"));
		var evidence = new WorldEvidence(Map.of(), Map.of("minecraft:wheat", 3), Map.of(),
			List.of(recipe), List.of(recipe), List.of(), List.of(), "minecraft:overworld", 1, 64, 2,
			"minecraft:air", 0, List.of(), 10);
		var mission = new MissionExecutionSnapshot(null, null, null, evidence, null, null);
		store.advanceClock(10, false, true);
		var first = DashboardObservationCollector.missionPayload(store, mission, 10, 100);
		store.advanceClock(30, false, true);
		var second = DashboardObservationCollector.missionPayload(store, mission, 30, 200);
		assertEquals(1, store.retainedObservations().size());
		assertEquals(30, store.retainedObservations().getFirst().throughServerTickId());
		assertEquals(first, second);
		var facts = (Map<?, ?>) second.get("evidence");
		assertEquals(Map.of("minecraft:wheat", 3), facts.get("itemCounts"));
		assertEquals(List.of(recipe), facts.get("availableCrafts"));
		assertEquals(store.retainedObservations().getFirst().sequence(), facts.get("recipeCatalogSequence"));
		assertTrue(!new Gson().toJson(second).contains("knownCrafts"));
		assertEquals(List.of(recipe), evidence.knownCrafts());
	}

	@Test
	void aPostDisconnectClientTickPreservesTheLastExportableWindow() {
		DashboardObservationStore store = new DashboardObservationStore(1024L * 1024L);
		store.advanceClock(500L, false, true);
		store.append("runtime_snapshot", 900L, 100L, Map.of("state", "failed"));
		DashboardObservationCollector collector = new DashboardObservationCollector(store);
		try {
			collector.worldLeft();
			collector.capture(null, null);
			assertEquals(500L, store.serverTickId());
			assertTrue(store.snapshot().paused());
			assertTrue(store.recordingPage(0L, store.serverTickId(), 0L, Long.MAX_VALUE, 10, Set.of(), false)
				.get("observations").toString().contains("failed"));
		}
		finally {
			collector.close();
		}
	}
}
