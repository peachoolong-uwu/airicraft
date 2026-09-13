package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.work.*;
import ai.moeru.airicraft.agent.actions.*;
import ai.moeru.airicraft.agent.tasks.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WorkProjectionTest {
	@Test void heldWorkKeepsItsRequestedDestinationWithoutConversationHistory() {
		var goal = new ai.moeru.airicraft.agent.goals.GoalSnapshot(ai.moeru.airicraft.agent.goals.GoalType.NAVIGATE_TO,
			null, new ai.moeru.airicraft.agent.goals.GoalPosition(4, 64, 8, true), null, 1, "planner_tool");
		var job = new ai.moeru.airicraft.agent.job.ActiveJob("nav", ai.moeru.airicraft.agent.job.ActiveJobType.NAVIGATE_TO,
			ai.moeru.airicraft.agent.job.ActiveJobStatus.BLOCKED, goal, null, null, null, 0, 0, 0, "planner_tool", "reflex", null, 10);
		var work = WorkProjection.project(job, TaskExecutionSnapshot.idle(), List.of(), List.of(), true, "hold", "nav", null, 11).getFirst();
		assertEquals(WorkSnapshot.State.PAUSED, work.state());
		assertEquals(goal, work.details().get("request"));
		assertEquals("hold", work.details().get("holdId"));
	}

	@Test void primitiveCleanupDoesNotPublishAnotherTerminalOutcome() {
		var job = new ai.moeru.airicraft.agent.job.ActiveJob("one", ai.moeru.airicraft.agent.job.ActiveJobType.COLLECT_RESOURCE,
			ai.moeru.airicraft.agent.job.ActiveJobStatus.COMPLETED, null, null, null, null, 0, 0, 3, "planner_tool", null, null, 100);
		var primitive = new TaskExecutionSnapshot(TaskExecutionState.RUNNING, "one", null, "acquisition", "APPROACH", null, null);
		var beforeCleanup = WorkProjection.project(job, primitive, List.of(), List.of(), false, null, null, null, 100).getFirst();
		var afterCleanup = WorkProjection.project(job, TaskExecutionSnapshot.idle(), List.of(), List.of(), false, null, null, null, 100).getFirst();
		assertEquals(beforeCleanup, afterCleanup);
		var history = new WorkHistory();
		assertTrue(history.observe(beforeCleanup));
		assertFalse(history.observe(afterCleanup));
		assertEquals("COMPLETED", afterCleanup.phase());
	}

	private ActionGraphExecutionView graph(String id) {
		return new ActionGraphExecutionView(ActionGraphResidency.SUSPENDED,0,0,0,
			new ActionGraphExecutionSnapshot(true,id,ActionGraphExecutionState.WATCHING,null,null,0,null,0,0,0,"", "", "", "",Map.of(),List.of(),List.of(),Map.of(),Map.of(),Map.of()));
	}
	@Test void onlyTheGraphIdentifiedByTheHoldIsPaused() {
		var result = WorkProjection.project(null,null,List.of(graph("held"),graph("background")),List.of(),true,"hold",null,"held",1);
		assertEquals(WorkSnapshot.State.PAUSED,result.get(0).state());
		assertEquals(WorkSnapshot.State.WAITING,result.get(1).state());
	}
	@Test void unknownFurnaceOutputIsVisibleWithoutInventingAnItem() {
		var process = new SmeltingProcessSnapshot("process","option",new SmeltingStationKey("minecraft:overworld",1,64,1),null,1,false);
		var result = WorkProjection.project(null,null,List.of(),List.of(process),false,null,null,null,1).getFirst();
		assertEquals(WorkSnapshot.State.WAITING,result.state());
		assertEquals(false,result.details().get("outputKnown"));
	}
}
