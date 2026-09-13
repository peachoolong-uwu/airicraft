package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.work.*;
import ai.moeru.airicraft.agent.actions.*;
import ai.moeru.airicraft.agent.tasks.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WorkProjectionTest {
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
