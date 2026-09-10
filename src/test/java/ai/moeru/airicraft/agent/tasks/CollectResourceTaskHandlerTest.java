package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.actions.BlockAcquisitionIndex;
import ai.moeru.airicraft.agent.actions.BlockAcquisitionRule;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CollectResourceTaskHandlerTest {
	@Test void carriesScopeIntoSystemOneMiningRequest() {
		CollectResourceTaskHandler handler = new CollectResourceTaskHandler();
		handler.updateBlockAcquisitions(BlockAcquisitionIndex.of(List.of(handRule("minecraft:oak_log", "minecraft:oak_log"))));
		var scope = new ai.moeru.airicraft.agent.goals.AcquisitionConstraints(new ai.moeru.airicraft.agent.goals.GoalPosition(10,64,20,true), 24, 8, true);
		var goal = handler.start(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 3, scope), 10L);
		assertEquals(scope, goal.mineSpec().constraints());
	}

	@Test
	void createsMineBlocksGoalForWoodLogs() {
		CollectResourceTaskHandler handler = new CollectResourceTaskHandler();
		handler.updateBlockAcquisitions(BlockAcquisitionIndex.of(List.of(
			handRule("minecraft:oak_log", "minecraft:oak_log"),
			handRule("minecraft:birch_log", "minecraft:birch_log")
		)));

		var goal = handler.start(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4), 120L);

		assertEquals(GoalType.MINE_BLOCKS, goal.type());
		assertEquals(List.of("minecraft:birch_log", "minecraft:oak_log"), goal.mineSpec().blockIds());
		assertEquals(ResourceGatheringCatalog.entry(TaskResourceKind.WOOD_LOGS).orElseThrow().acceptedItemIds(), goal.mineSpec().matchingItemIds());
	}

	@Test
	void createsMineBlocksGoalForCatalogedOreResource() {
		CollectResourceTaskHandler handler = new CollectResourceTaskHandler();
		handler.updateBlockAcquisitions(BlockAcquisitionIndex.of(List.of(
			handRule("minecraft:deepslate_iron_ore", "minecraft:raw_iron"),
			handRule("minecraft:iron_ore", "minecraft:raw_iron")
		)));

		var goal = handler.start(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.RAW_IRON, 3), 120L);

		assertEquals(GoalType.MINE_BLOCKS, goal.type());
		assertEquals(new GoalMineSpec(
			List.of("minecraft:deepslate_iron_ore", "minecraft:iron_ore"),
			3,
			List.of("minecraft:raw_iron"),
			List.of()
		), goal.mineSpec());
	}

	private static BlockAcquisitionRule handRule(String blockId, String outputItemId) {
		return new BlockAcquisitionRule(blockId, outputItemId, List.of(), true, false, "test:" + blockId.substring(blockId.indexOf(':') + 1));
	}
}
