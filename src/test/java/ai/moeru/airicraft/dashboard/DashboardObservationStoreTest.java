package ai.moeru.airicraft.dashboard;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardObservationStoreTest {
	@Test
	void recordsVersionedObservationsWithinOneSession() {
		DashboardObservationStore store = new DashboardObservationStore(1024L * 1024L);
		String sessionId = store.startSession("client_started", 0L, 100L);
		store.append("semantic_event", 3L, 150L, Map.of("type", "task.started"));

		DashboardObservationStore.Query query = store.queryAfter(0L, 100);

		assertEquals(sessionId, query.sessionId());
		assertEquals(2, query.observations().size());
		assertEquals("session_started", query.observations().get(0).type());
		assertEquals("semantic_event", query.observations().get(1).type());
		assertEquals(3L, query.latestTick());
		assertFalse(query.truncated());
	}

	@Test
	void worldOrRuntimeSessionChangeDiscardsUnrelatedHistory() {
		DashboardObservationStore store = new DashboardObservationStore(1024L * 1024L);
		String first = store.startSession("client_started", 0L, 100L);
		store.append("runtime_snapshot", 5L, 150L, Map.of("state", "idle"));
		String second = store.startSession("runtime_reloaded", 6L, 200L);

		assertNotEquals(first, second);
		assertEquals(1, store.retainedObservations().size());
		assertEquals(second, store.retainedObservations().getFirst().sessionId());
	}

	@Test
	void retentionUsesServerTicksAndDoesNotAgeWhilePaused() {
		DashboardObservationStore store = new DashboardObservationStore(1024L * 1024L);
		store.advanceClock(50L, false, true);
		store.append("decision_state", 900_000L, 100L, Map.of("reason", "escape_failed"));
		store.advanceClock(12_050L, true, true);
		store.appendLog("while paused", 9_000_000L);
		assertEquals(1, store.retainedObservations().size());
		assertEquals(50L, store.retainedObservations().getFirst().serverTickId());
		assertTrue(store.snapshot().paused());
		store.advanceClock(12_051L, true, true); // A single debug step expires exactly one tick of history.
		assertTrue(store.retainedObservations().isEmpty());
	}

	@Test
	void identicalFrameExtendsValidityWithoutAnotherRecordAndStaleCaptureCannotCrossSessions() {
		DashboardObservationStore store = new DashboardObservationStore(1024L * 1024L);
		String session = store.startSession("world_joined", 0L, 0L);
		var frame = store.appendFrame(session, 5L, 5L, 100L, Map.of("imageBase64", "AA=="));
		assertTrue(store.extendFrame(session, frame.sequence(), 12_004L));
		store.advanceClock(12_006L, false, true);
		assertEquals(1, store.retainedObservations().size());
		assertEquals(12_004L, store.retainedObservations().getFirst().throughServerTickId());
		store.startSession("world_joined", 0L, 200L);
		assertEquals(null, store.appendFrame(session, 6L, 6L, 300L, Map.of("imageBase64", "AA==")));
		assertFalse(store.extendFrame(session, frame.sequence(), 12_007L));
	}

	@Test
	void recordingPagesFilterBeforeLimitingAndOmitImageBytesByDefault() {
		DashboardObservationStore store = new DashboardObservationStore(1024L * 1024L);
		store.advanceClock(50L, false, true);
		store.append("log", 5L, 1L, Map.of("message", "noise"));
		store.appendFrame(store.sessionId(), 5L, 50L, 1L, Map.of("imageBase64", "AA==", "width", 640));
		store.append("decision_state", 5L, 1L, Map.of("reason", "failed"));
		var page = store.recordingPage(50L, 50L, 0L, Long.MAX_VALUE, 1, java.util.Set.of("visual_frame", "decision_state"), false);
		var records = (java.util.List<?>) page.get("observations");
		assertEquals(1, records.size());
		assertFalse(records.getFirst().toString().contains("imageBase64"));
		assertTrue((boolean) page.get("hasMore"));
		long cursor = ((Number) page.get("nextCursor")).longValue();
		var next = store.recordingPage(50L, 50L, cursor, Long.MAX_VALUE, 1, java.util.Set.of("visual_frame", "decision_state"), false);
		assertFalse((boolean) next.get("hasMore"));
		assertTrue(next.get("observations").toString().contains("failed"));
		assertTrue(store.framePayload(cursor).toString().contains("AA=="));
	}

	@Test
	void imagesCannotEvictAllDecisionEvidence() {
		DashboardObservationStore store = new DashboardObservationStore(1024L * 1024L);
		store.append("decision_state", 0L, 0L, Map.of("reason", "failed"));
		for (int i = 0; i < 5; i++) {
			store.appendFrame(store.sessionId(), i, i, i, Map.of("imageBase64", "x".repeat(100_000)));
		}
		assertEquals("decision_state", store.retainedObservations().getFirst().type());
		assertTrue(((Number) store.recordingStatus().get("visualBytes")).longValue() <= 512L * 1024L);
	}

	@Test
	void reportsCursorGapsAfterBudgetEviction() {
		DashboardObservationStore store = new DashboardObservationStore(1024L * 1024L);
		store.startSession("client_started", 0L, 100L);
		String large = "x".repeat(400_000);
		for (int index = 0; index < 5; index++) {
			store.append("log", index, 200L + index, Map.of("message", large));
		}

		DashboardObservationStore.Query query = store.queryAfter(0L, 100);

		assertTrue(query.truncated());
		assertTrue(query.oldestSequence() > 1L);
		assertTrue(query.droppedByType().getOrDefault("log", 0L) > 0L);
		assertTrue(query.retainedBytes() <= query.maxBytes());
	}
}
