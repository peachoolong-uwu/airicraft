package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.work.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorkHistoryTest {
	private WorkSnapshot snapshot(String id, String parent, WorkSnapshot.State state) {
		return new WorkSnapshot(WorkHandle.of(WorkHandle.Kind.JOB, id), parent, state, "job", state.name(), false, 1, Map.of());
	}
	@Test void boundsTerminalHistoryWithoutEvictingUnresolvedWorkOrLosingParents() {
		var history = new WorkHistory();
		history.observe(snapshot("held", "", WorkSnapshot.State.PAUSED));
		history.observe(snapshot("child", "GRAPH:parent", WorkSnapshot.State.RUNNING));
		for (int i = 0; i < 150; i++) history.observe(snapshot("done" + i, "", WorkSnapshot.State.SUCCEEDED));
		assertEquals(130, history.list().size());
		assertEquals("JOB:held", history.current().orElseThrow().handle().id());
		history.observe(snapshot("child", "", WorkSnapshot.State.FAILED));
		assertEquals("GRAPH:parent", history.find(new WorkHandle("JOB:child")).orElseThrow().parentWorkId());
	}
	@Test void identicalObservationDoesNotWakeAgain() {
		var history = new WorkHistory();
		assertTrue(history.observe(snapshot("one", "", WorkSnapshot.State.RUNNING)));
		assertFalse(history.observe(snapshot("one", "", WorkSnapshot.State.RUNNING)));
		assertTrue(history.observe(snapshot("one", "", WorkSnapshot.State.FAILED)));
		assertFalse(history.observe(snapshot("one", "", WorkSnapshot.State.FAILED)));
	}
}
