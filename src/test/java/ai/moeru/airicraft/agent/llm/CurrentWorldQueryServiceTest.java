package ai.moeru.airicraft.agent.llm;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CurrentWorldQueryServiceTest {
	@Test
	void areaResultBoundsOutputAndOnlyAuthorizesReturnedPositions() {
		BlockPos nearest = new BlockPos(1, 64, 1);
		BlockPos second = new BlockPos(2, 64, 2);
		BlockPos omitted = new BlockPos(3, 64, 3);
		List<CurrentWorldQueryService.BlockRecord> records = List.of(
			record(omitted, 3),
			record(nearest, 1),
			record(second, 2)
		);

		CurrentWorldQueryService.WorldQueryResult result = CurrentWorldQueryService.areaResult(
			new CurrentWorldQueryService.QueryBounds("center", new BlockPos(-1, 63, -1), new BlockPos(3, 65, 3)),
			75,
			records,
			2
		);

		assertTrue(result.text().contains("scanned=75 matched=3 returned=2"), result.text());
		assertTrue(result.text().contains("pos=1,64,1"), result.text());
		assertTrue(result.text().contains("pos=2,64,2"), result.text());
		assertFalse(result.text().contains("pos=3,64,3"), result.text());
		assertEquals(List.of(nearest, second), result.observedPositions());
	}

	@Test
	void movingInspectionCenterChangesTheLimitedNeighborhood() {
		BlockPos player = new BlockPos(0, 133, 4);
		var centers = List.of(new BlockPos(0, 134, 4), new BlockPos(-2, 133, 6));
		var results = new java.util.ArrayList<CurrentWorldQueryService.WorldQueryResult>();
		for (BlockPos center : centers) {
			var bounds = new CurrentWorldQueryService.QueryBounds("center", center.add(-5, -5, -5), center.add(5, 5, 5));
			var records = bounds.positions().stream().map(pos -> record(pos, Math.max(
				Math.max(Math.abs(pos.getX() - player.getX()), Math.abs(pos.getY() - player.getY())),
				Math.abs(pos.getZ() - player.getZ())))).toList();
			var result = CurrentWorldQueryService.areaResult(bounds, records.size(), records, 32);
			assertEquals(center, result.observedPositions().getFirst());
			assertEquals(32, result.observedPositions().size());
			assertTrue(result.text().contains("truncated=true"), result.text());
			results.add(result);
		}
		assertFalse(results.get(0).observedPositions().equals(results.get(1).observedPositions()));
		// Distances still describe reach from the player, not distance from the requested center.
		assertTrue(results.get(1).text().contains("pos=-2,133,6, id=minecraft:stone, loaded=true, replaceable=false, air=false, fluid=false, distance=2"));
	}

	private static CurrentWorldQueryService.BlockRecord record(BlockPos pos, int distance) {
		return new CurrentWorldQueryService.BlockRecord(
			pos,
			"minecraft:stone",
			Map.of(),
			true,
			false,
			false,
			false,
			distance
		);
	}

	@Test
	void smallBoxLayersKeepCoordinatesStatesAndUnknownCellsDistinct() {
		var bounds = new CurrentWorldQueryService.QueryBounds("box", new BlockPos(0, 134, 0), new BlockPos(1, 135, 1));
		var records = new java.util.ArrayList<CurrentWorldQueryService.BlockRecord>();
		records.add(new CurrentWorldQueryService.BlockRecord(new BlockPos(0, 134, 0), "minecraft:air", Map.of(), true, true, true, false, 0));
		records.add(new CurrentWorldQueryService.BlockRecord(new BlockPos(0, 134, 1), "minecraft:snow", Map.of("layers", "1"), true, true, false, false, 0));
		records.add(new CurrentWorldQueryService.BlockRecord(new BlockPos(0, 135, 0), "minecraft:oak_door", Map.of("half", "upper", "open", "false"), true, false, false, false, 0));
		records.add(CurrentWorldQueryService.BlockRecord.unloaded(new BlockPos(0, 135, 1), 0));
		for (int y = 134; y <= 135; y++) for (int z = 0; z <= 1; z++) records.add(record(new BlockPos(1, y, z), 2));

		var result = CurrentWorldQueryService.areaResult(bounds, 8, records, 4);
		assertTrue(result.text().contains("columns X: 0 1\nY=134\nZ=0: 0 ?\nZ=1: 2 ?\nY=135\nZ=0: 1 ?\nZ=1: 3 ?"), result.text());
		assertTrue(result.text().contains("layers=1"));
		assertTrue(result.text().contains("half=upper"));
		assertTrue(result.text().contains("open=false"));
		assertTrue(result.text().contains("id=unloaded, loaded=false"));
		assertTrue(result.text().contains("?=omitted, not observed"));
		assertTrue(result.text().contains("truncated=true"));
		assertEquals(4, result.observedPositions().size());
		assertTrue(result.observedPositions().stream().allMatch(pos -> pos.getX() == 0));
	}

	@Test
	void repetitiveShelterLayerUsesLessTextWithoutLosingObservedCoordinates() {
		var bounds = new CurrentWorldQueryService.QueryBounds("box", new BlockPos(-4, 136, 2), new BlockPos(0, 136, 6));
		var records = bounds.positions().stream().map(pos -> record(pos, 1)).toList();
		var result = CurrentWorldQueryService.areaResult(bounds, 25, records, 64);
		int verboseLength = records.stream().mapToInt(record -> record.compact().length()).sum();
		assertTrue(result.text().length() < verboseLength / 2, result.text());
		assertEquals(25, result.observedPositions().size());
		assertTrue(result.observedPositions().containsAll(bounds.positions()));
		assertTrue(result.text().contains("columns X: -4 -3 -2 -1 0\nY=136\nZ=2: 0 0 0 0 0"));
		assertTrue(result.text().contains("truncated=false"));
	}
}
