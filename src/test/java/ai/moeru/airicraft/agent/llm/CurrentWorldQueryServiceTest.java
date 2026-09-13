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
}
