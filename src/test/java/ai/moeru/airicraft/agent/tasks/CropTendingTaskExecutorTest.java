package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ai.moeru.airicraft.agent.tasks.CropTendingTaskExecutor.Cell;

class CropTendingTaskExecutorTest {
	@Test void waitsForPickupDelayWithoutStartingAPathAtTheCurrentGoal() {
		Fixture f = new Fixture();
		f.env.cells.put(pos(0), Cell.MATURE);
		f.env.drop = pos(0);
		f.nav.goalReached = true;
		f.tick(30);
		assertTrue(f.nav.goals.isEmpty(), "An already-reached pickup goal needs no path");
		assertTrue(f.planting.planted.isEmpty());
		f.env.drop = null;
		f.tick(10);
		assertEquals(TaskExecutionState.COMPLETED, f.events.getFirst().terminalState());
	}

	@Test void nearbyCropIsNotProofThatItsScatteredDropsWereCollected() {
		Fixture f = new Fixture();
		f.env.cells.put(pos(0), Cell.MATURE);
		f.env.drop = pos(1);
		f.tick(40);
		assertTrue(f.planting.planted.isEmpty(), "Do not replant while harvest drops remain nearby");
		assertTrue(f.nav.goals.contains(pos(1)));
		assertTrue(f.events.isEmpty());
		// The first pile is picked up, but a second pile remains on the other side.
		f.env.drop = pos(-1);
		f.tick(4);
		assertTrue(f.nav.goals.contains(pos(-1)));
		assertTrue(f.planting.planted.isEmpty());
		f.env.drop = null;
		f.tick(30);
		assertEquals(List.of(pos(0)), f.planting.planted);
		assertEquals(TaskExecutionState.COMPLETED, f.events.getFirst().terminalState());
	}

	@Test void uncollectedDropsTimeOutInsteadOfReportingHarvestSuccess() {
		Fixture f = new Fixture();
		f.env.cells.put(pos(0), Cell.MATURE);
		f.env.drop = pos(1);
		f.tick(250);
		assertTrue(f.planting.planted.isEmpty());
		assertEquals(TaskExecutionState.FAILED, f.events.getFirst().terminalState());
		assertFalse(f.nav.active);
	}

	@Test void harvestsReplantsAndUsesSurplusSeedsOnEmptyFarmlandInOnePass() {
		Fixture f = new Fixture();
		f.env.cells.put(pos(0), Cell.MATURE);
		f.env.cells.put(pos(1), Cell.EMPTY_FARMLAND);
		f.env.cells.put(pos(2), Cell.GROWING);
		f.tick(80);
		assertEquals(List.of(pos(0)), f.env.harvests);
		assertEquals(List.of(pos(0), pos(1)), f.planting.planted);
		assertEquals(1, f.events.size());
		assertTrue(f.events.getFirst().message().contains("harvested=1 planted=2 growing=1 missingSeedPlots=0"));
		assertFalse(f.nav.active);
	}

	@Test void growingCropsAndUnrelatedBlocksAreLeftAloneAndMissingSeedsAreReported() {
		Fixture f = new Fixture();
		f.env.cells.put(pos(0), Cell.GROWING);
		f.env.cells.put(pos(1), Cell.OTHER);
		f.env.cells.put(pos(2), Cell.EMPTY_FARMLAND);
		f.tick(3);
		assertTrue(f.env.harvests.isEmpty());
		assertTrue(f.planting.planted.isEmpty());
		assertTrue(f.nav.goals.isEmpty());
		assertTrue(f.events.getFirst().message().contains("growing=1 missingSeedPlots=1"));
	}

	@Test void cropChangedDuringApproachIsNotBroken() {
		Fixture f = new Fixture();
		f.env.cells.put(pos(0), Cell.MATURE);
		f.env.reachable = false;
		f.tick(2);
		assertTrue(f.nav.active);
		f.env.cells.put(pos(0), Cell.GROWING);
		f.tick(4);
		assertTrue(f.env.harvests.isEmpty());
		assertFalse(f.nav.active);
	}

	@Test void pausesReleaseNavigationWithoutSpendingTheWorkBudget() {
		Fixture f = new Fixture();
		f.env.cells.put(pos(0), Cell.MATURE);
		f.env.reachable = false;
		f.tick(2);
		for (int i = 0; i < 3000; i++) f.executor.tick(SessionSnapshot.initial(), Optional.of(f.request));
		assertFalse(f.nav.active);
		assertTrue(f.events.isEmpty());
		f.env.reachable = true;
		f.tick(60);
		assertEquals(TaskExecutionState.COMPLETED, f.events.getFirst().terminalState());
	}

	@Test void stalledApproachFailsAndReleasesMovement() {
		Fixture f = new Fixture();
		f.env.cells.put(pos(0), Cell.MATURE);
		f.env.reachable = false;
		f.tick(250);
		assertEquals(TaskExecutionState.FAILED, f.events.getFirst().terminalState());
		assertTrue(f.env.harvests.isEmpty());
		assertFalse(f.nav.active);
	}

	@Test void invalidOrUnloadedPlotFailsBeforeAnyAction() {
		Fixture invalid = new Fixture();
		invalid.env.validationError = "unsupported_crop_planting_item";
		invalid.tick(1);
		assertEquals(TaskExecutionState.FAILED, invalid.events.getFirst().terminalState());
		Fixture unloaded = new Fixture();
		unloaded.env.cells.put(pos(0), Cell.UNLOADED);
		unloaded.tick(1);
		assertEquals(TaskExecutionState.FAILED, unloaded.events.getFirst().terminalState());
	}

	@Test void rejectsOversizedReversedAndFractionalPlotBounds() {
		for (String bounds : List.of("\"x1\":0,\"x2\":16", "\"x1\":1,\"x2\":0", "\"x1\":0.5,\"x2\":1")) {
			String json = "{\"seedItemId\":\"minecraft:wheat_seeds\",\"y\":63,\"z1\":0,\"z2\":0," + bounds + "}";
			assertThrows(IllegalArgumentException.class, () -> CropTendingStepArgs.parse(com.google.gson.JsonParser.parseString(json).getAsJsonObject()));
		}
	}

	private static GoalPosition pos(int x) { return new GoalPosition(x, 63, 0, true); }
	private static final class Fixture {
		final FakeEnvironment env = new FakeEnvironment();
		final TargetAcquisitionTaskExecutorTest.FakeNavigation nav = new TargetAcquisitionTaskExecutorTest.FakeNavigation();
		final FakePlanting planting = new FakePlanting(env);
		final CropTendingTaskExecutor executor = new CropTendingTaskExecutor(nav, env, planting);
		final WorldTaskRequest request = WorldTaskRequest.tendCrops("tend", "job", new CropTendingStepArgs("minecraft:wheat_seeds", 0, 63, 0, 2, 0));
		final List<TaskTerminalEvent> events = new ArrayList<>();
		long tick;
		void tick(int n) { for (int i = 0; i < n; i++) executor.tick(new SessionSnapshot(SessionMode.SINGLEPLAYER_LAN_HOST,
			true, true, "minecraft:overworld", true, 25565, ++tick), Optional.of(request)).ifPresent(events::add); }
	}

	private static final class FakeEnvironment implements CropTendingTaskExecutor.Environment {
		final Map<GoalPosition, Cell> cells = new HashMap<>();
		final List<GoalPosition> harvests = new ArrayList<>();
		int seeds;
		boolean reachable = true;
		String validationError;
		GoalPosition drop;
		public Optional<GoalPosition> pickupPosition(GoalPosition crop, CropTendingStepArgs args) { return Optional.ofNullable(drop); }
		public String validate(CropTendingStepArgs args) { return validationError; }
		public Cell state(GoalPosition pos, CropTendingStepArgs args) { return cells.getOrDefault(pos, Cell.OTHER); }
		public GoalPosition position() { return pos(0); }
		public GoalPosition workPosition(GoalPosition crop) { return pos(1); }
		public boolean canHarvest(GoalPosition crop) { return reachable; }
		public boolean harvest(GoalPosition crop, CropTendingStepArgs args) {
			assertEquals(Cell.MATURE, cells.get(crop));
			harvests.add(crop); cells.put(crop, Cell.EMPTY_FARMLAND); seeds += 2; return true;
		}
		public int seedCount(String item) { return seeds; }
	}

	/** Models the crop appearing before the planting executor emits its completion. */
	private static final class FakePlanting implements WorldTaskExecutor {
		final FakeEnvironment env;
		final List<GoalPosition> planted = new ArrayList<>();
		WorldTaskRequest pending;
		FakePlanting(FakeEnvironment env) { this.env = env; }
		public Optional<TaskTerminalEvent> tick(SessionSnapshot session, Optional<WorldTaskRequest> task) {
			if (task.isEmpty()) { pending = null; return Optional.empty(); }
			var request = task.get();
			if (pending == null) {
				pending = request;
				var pos = ((WorldTaskRequest.UseBlock) request.task()).args().targetPosition();
				assertEquals(Cell.EMPTY_FARMLAND, env.cells.get(pos));
				assertTrue(env.seeds > 0);
				env.seeds--; env.cells.put(pos, Cell.GROWING); planted.add(pos);
				return Optional.empty();
			}
			return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.COMPLETED, "planted", TaskTerminationCause.GOAL_REACHED));
		}
		public TaskExecutionSnapshot snapshot() { return TaskExecutionSnapshot.idle(); }
		public void onWorldLeave() { pending = null; }
		public void shutdown() { onWorldLeave(); }
	}
}
