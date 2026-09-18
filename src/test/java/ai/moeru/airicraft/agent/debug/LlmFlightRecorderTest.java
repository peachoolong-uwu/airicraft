package ai.moeru.airicraft.agent.debug;

import ai.moeru.airicraft.agent.llm.LlmUsageSnapshot;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class LlmFlightRecorderTest {
    @Test void repeatedLargeRequestsEvictByBytesBeforeCallCount() {
        var recorder = new LlmFlightRecorder(2048, 10_000);
        for (int i = 0; i < 20; i++) {
            request(recorder, "x".repeat(2_000) + i);
            recorder.recordFailure("provider", "HTTP 400");
        }
        var result = recorder.query(null);
        assertTrue(result.truncated());
        assertEquals(20, result.latestSequenceId());
        assertTrue(result.records().size() <= 2);
        assertEquals("x".repeat(2_000) + 19, result.records().getLast().requestBody());
        assertFalse(recorder.query(result.oldestSequenceId() - 1).truncated());
    }

    @Test void responseGrowthAlsoEvictsOldRecords() {
        var recorder = new LlmFlightRecorder(2048, 10_000);
        for (int i = 0; i < 10; i++) {
            request(recorder, "small request");
            recorder.recordRawResponse(200, "model", LlmUsageSnapshot.unknown(), "r".repeat(2_000));
            recorder.recordParsedResponse("reply", 200, "model", LlmUsageSnapshot.unknown(), "p".repeat(2_000));
        }
        assertTrue(recorder.query(null).truncated());
        assertEquals(1, recorder.query(null).records().size());
        assertEquals("p".repeat(2_000), recorder.query(null).records().getFirst().parsedResponse());
    }

    @Test void oversizedRecordIsDroppedAndDoesNotReappearAsSyntheticCompletion() {
        var recorder = new LlmFlightRecorder(2048, 10_000);
        request(recorder, "x".repeat(10_000));
        assertTrue(recorder.query(null).records().isEmpty());
        assertTrue(recorder.query(null).truncated());
        assertEquals(1, recorder.query(null).latestSequenceId());
        recorder.streamListener().accept("streamed response");
        recorder.recordRawResponse(200, "model", LlmUsageSnapshot.unknown(), "response");
        recorder.recordParsedResponse("reply", 200, "model", LlmUsageSnapshot.unknown(), "response");
        assertTrue(recorder.query(null).records().isEmpty());
        request(recorder, "next");
        assertEquals(2, recorder.query(null).records().getFirst().sequenceId());
    }

    @Test void evictedPendingCallCannotResurrectPayloadThroughStreamingCallback() throws Exception {
        var recorder = new LlmFlightRecorder(2048, 10_000);
        var ready = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pending = CompletableFuture.runAsync(() -> {
            request(recorder, "x".repeat(2_000));
            var stream = recorder.streamListener();
            ready.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
            stream.accept("late response");
            recorder.recordParsedResponse("reply", 200, "model", LlmUsageSnapshot.unknown(), "late response");
        });
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            request(recorder, "y".repeat(3_000));
            recorder.recordFailure("provider", "failed");
        } finally { release.countDown(); }
        pending.get(5, TimeUnit.SECONDS);
        var result = recorder.query(null);
        assertEquals(2, result.latestSequenceId());
        assertEquals(1, result.records().size());
        assertEquals(2, result.records().getFirst().sequenceId());
    }

    private static void request(LlmFlightRecorder recorder, String body) {
        recorder.recordRequest("compaction", "thread", "provider", null, "model", 1000, null, body);
    }
}
