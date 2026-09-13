package ai.moeru.airicraft.agent.llm;

import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Local standing-player clearance, independent of the door's nominal open flag. */
final class DoorPassageGeometry {
	private DoorPassageGeometry() {}

	static BlockPos lowerPos(BlockPos pos, BlockState state) {
		return state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.down() : pos;
	}

	static Optional<String> describe(World world, BlockPos inspected) {
		BlockState inspectedState = world.getBlockState(inspected);
		if (!(inspectedState.getBlock() instanceof DoorBlock)) return Optional.empty();
		BlockPos lower = lowerPos(inspected, inspectedState);
		String prefix = "door=" + pos(lower);
		for (BlockPos cell : BlockPos.iterate(lower.add(-1, -1, -1), lower.add(1, 1, 1))) {
			if (!world.isChunkLoaded(cell)) return Optional.of(prefix + " passage=unknown reason=neighbor_unloaded");
		}
		BlockState bottom = world.getBlockState(lower);
		BlockState top = world.getBlockState(lower.up());
		if (!(bottom.getBlock() instanceof DoorBlock) || top.getBlock() != bottom.getBlock()
			|| bottom.get(DoorBlock.HALF) != DoubleBlockHalf.LOWER || top.get(DoorBlock.HALF) != DoubleBlockHalf.UPPER
			|| bottom.get(DoorBlock.OPEN) != top.get(DoorBlock.OPEN)
			|| bottom.get(DoorBlock.FACING) != top.get(DoorBlock.FACING)
			|| bottom.get(DoorBlock.HINGE) != top.get(DoorBlock.HINGE)) {
			return Optional.of(prefix + " passage=unknown reason=incomplete_or_inconsistent_door");
		}
		List<Box> current = new ArrayList<>();
		List<Box> toggled = new ArrayList<>();
		for (BlockPos cell : List.of(lower, lower.up())) {
			BlockState state = world.getBlockState(cell);
			current.addAll(boxes(world, cell, lower, state));
			toggled.addAll(boxes(world, cell, lower, state.with(DoorBlock.OPEN, !state.get(DoorBlock.OPEN))));
		}
		List<Box> surroundings = new ArrayList<>();
		for (BlockPos cell : BlockPos.iterate(lower.add(-1, -1, -1), lower.add(1, 1, 1))) {
			if (cell.equals(lower) || cell.equals(lower.up())) continue;
			surroundings.addAll(boxes(world, cell, lower, world.getBlockState(cell)));
		}
		Assessment result = assess(current, toggled, surroundings);
		StringBuilder text = new StringBuilder(prefix).append(" stateOpen=").append(bottom.get(DoorBlock.OPEN))
			.append(" handToggleAvailable=").append(DoorBlock.canOpenByHand(bottom))
			.append(" powered=").append(bottom.get(DoorBlock.POWERED))
			.append(" inferredPassage=").append(result.inferredPassage())
			.append(" (local level clearance; stateOpen does not determine passage)");
		text.append("\n  east_west: ").append(result.eastWest().description())
			.append("; ").append(approach(world, lower.west(), "west"))
			.append("; ").append(approach(world, lower.east(), "east"));
		text.append("\n  north_south: ").append(result.northSouth().description())
			.append("; ").append(approach(world, lower.north(), "north"))
			.append("; ").append(approach(world, lower.south(), "south"));
		return Optional.of(text.toString());
	}

	private static List<Box> boxes(World world, BlockPos cell, BlockPos origin, BlockState state) {
		return state.getCollisionShape(world, cell).getBoundingBoxes().stream()
			.map(box -> box.offset(cell.getX() - origin.getX(), cell.getY() - origin.getY(), cell.getZ() - origin.getZ()))
			.toList();
	}

	private static String approach(World world, BlockPos feet, String side) {
		BlockPos floor = feet.down();
		boolean supported = world.getBlockState(floor).isSideSolidFullSquare(world, floor, Direction.UP);
		return side + "Feet=" + pos(feet) + " levelFloor=" + (supported ? "supported" : "not_full_support");
	}

	static Assessment assess(List<Box> currentDoor, List<Box> toggledDoor, List<Box> surroundings) {
		// Sweep a 0.6-wide, 1.8-high player between centers of the neighboring cells.
		Axis eastWest = axis(new Box(-0.8D, 0D, 0.2D, 1.8D, 1.8D, 0.8D), currentDoor, toggledDoor, surroundings);
		Axis northSouth = axis(new Box(0.2D, 0D, -0.8D, 0.8D, 1.8D, 1.8D), currentDoor, toggledDoor, surroundings);
		String inferred = eastWest.surroundingsClear() == northSouth.surroundingsClear() ? "ambiguous"
			: eastWest.surroundingsClear() ? "east_west" : "north_south";
		return new Assessment(inferred, eastWest, northSouth);
	}

	private static Axis axis(Box corridor, List<Box> current, List<Box> toggled, List<Box> surroundings) {
		return new Axis(surroundings.stream().noneMatch(corridor::intersects),
			current.stream().anyMatch(corridor::intersects), toggled.stream().anyMatch(corridor::intersects));
	}

	private static String pos(BlockPos pos) {
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	record Assessment(String inferredPassage, Axis eastWest, Axis northSouth) {}

	record Axis(boolean surroundingsClear, boolean doorBlocksNow, boolean doorBlocksAfterToggle) {
		String description() {
			String action = !surroundingsClear ? "inspect_surrounding_obstruction"
				: !doorBlocksNow ? "leave_as_is" : !doorBlocksAfterToggle ? "toggle_door" : "no_clear_state";
			return "surroundingsClear=" + surroundingsClear + " doorBlocksNow=" + doorBlocksNow
				+ " doorBlocksAfterToggle=" + doorBlocksAfterToggle + " toClear=" + action;
		}
	}
}
