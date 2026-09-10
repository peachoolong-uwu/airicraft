package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.actions.BlockAcquisitionIndex;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;

public final class CollectResourceTaskHandler {
	private BlockAcquisitionIndex blockAcquisitions = BlockAcquisitionIndex.empty();

	public void updateBlockAcquisitions(BlockAcquisitionIndex blockAcquisitions) {
		this.blockAcquisitions = blockAcquisitions == null ? BlockAcquisitionIndex.empty() : blockAcquisitions;
	}

	public GoalSnapshot start(TaskSpec spec, long tick) {
		return start(spec, spec.quantity(), tick);
	}

	public GoalSnapshot start(TaskSpec spec, int remainingQuantity, long tick) {
		if (spec.type() != TaskType.COLLECT_RESOURCE) {
			throw new IllegalArgumentException("Unsupported task spec: " + spec);
		}
		GoalSnapshot goal = start(spec.resourceKind(), remainingQuantity, tick);
		return new GoalSnapshot(goal.type(), goal.targetPlayer(), goal.position(),
			goal.mineSpec().withConstraints(spec.constraints()), goal.updatedTick(), goal.source());
	}

	public GoalSnapshot start(TaskResourceKind resourceKind, int remainingQuantity, long tick) {
		ResourceGatheringCatalog.ResourceEntry entry = ResourceGatheringCatalog.entry(resourceKind)
			.orElseThrow(() -> new IllegalArgumentException("Unsupported resource kind: " + resourceKind));
		java.util.List<String> blockIds = blockAcquisitions.sourceBlockIdsForOutputs(entry.acceptedItemIds());
		if (blockIds.isEmpty()) {
			throw new IllegalArgumentException("No loaded block acquisition route for resource kind: " + resourceKind);
		}
		return new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(blockIds, remainingQuantity, entry.acceptedItemIds(), java.util.List.of()),
			tick,
			"task_runtime"
		);
	}

	public java.util.List<String> targetBlockIds(TaskSpec spec) {
		if (spec == null || spec.type() != TaskType.COLLECT_RESOURCE) {
			return java.util.List.of();
		}
		return ResourceGatheringCatalog.entry(spec.resourceKind())
			.map(ResourceGatheringCatalog.ResourceEntry::acceptedItemIds)
			.map(blockAcquisitions::sourceBlockIdsForOutputs)
			.orElse(java.util.List.of());
	}

	public boolean matchesResourceKind(TaskResourceKind resourceKind, java.util.List<String> blockIds) {
		java.util.List<String> resourceBlocks = ResourceGatheringCatalog.entry(resourceKind)
			.map(ResourceGatheringCatalog.ResourceEntry::acceptedItemIds)
			.map(blockAcquisitions::sourceBlockIdsForOutputs)
			.orElse(java.util.List.of());
		if (resourceBlocks.isEmpty() || blockIds == null || blockIds.isEmpty()) {
			return false;
		}
		return blockIds.stream().allMatch(resourceBlocks::contains);
	}
}
