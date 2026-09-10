package ai.moeru.airicraft.dashboard;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardObservationCollectorTest {
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
