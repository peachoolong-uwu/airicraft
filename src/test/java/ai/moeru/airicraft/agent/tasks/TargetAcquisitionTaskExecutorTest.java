package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.*;
import ai.moeru.airicraft.agent.session.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ai.moeru.airicraft.agent.tasks.TargetAcquisitionTaskExecutor.*;

class TargetAcquisitionTaskExecutorTest {
	@Test void workSearchIncludesGroundFromWhichAnOverheadLogIsInEyeReach() {
		var log = new net.minecraft.util.math.BlockPos(284,69,-138);
		var feet = new net.minecraft.util.math.BlockPos(285,64,-138);
		var eye = net.minecraft.util.math.Vec3d.ofBottomCenter(feet).add(0,1.62,0);
		assertTrue(eye.squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(log)) <= 20.25);
		boolean included = false;
		for (var pos : MinecraftAcquisitionEnvironment.workPositions(log)) if (pos.equals(feet)) included = true;
		assertTrue(included, "Reachable ground must be considered before requiring a higher platform");
	}

	@Test void acquiresWithoutAnotherPlannerCallAndWaitsForInventory() {
		Fixture f = new Fixture();
		f.env.interactable = true;
		f.tick(4);
		assertEquals(1, f.env.breaks);
		assertEquals(TaskExecutionState.RUNNING, f.executor.snapshot().state());
		f.env.sources = List.of(new Candidate(Kind.DROP, "drop", f.env.position, f.env.position));
		f.tick(23);
		assertEquals(TaskExecutionState.RUNNING, f.executor.snapshot().state());
		f.env.count = 1;
		f.tick(1);
		assertEquals(TaskExecutionState.COMPLETED, f.executor.snapshot().state());
		assertEquals(1, f.events.size());
		f.tick(2);
		assertEquals(1, f.events.size());
	}

	@Test void abandonsStalledApproachAndTriesAnotherObservedTarget() {
		Fixture f = new Fixture();
		Candidate alternative = new Candidate(Kind.BLOCK, "log", pos(2,64,0), pos(1,64,0));
		f.env.sources = List.of(f.env.sources.getFirst(), alternative);
		f.tick(86);
		assertTrue(f.env.rejections > 0);
		assertTrue(f.nav.goals.contains(alternative.workPosition()));
		assertEquals(TaskExecutionState.RUNNING, f.executor.snapshot().state());
	}

	@Test void surfaceScopeExitCancelsMovementAndReportsPartialCount() {
		Fixture f = new Fixture();
		f.tick(2);
		assertTrue(f.nav.active);
		f.env.inScope = false;
		f.tick(1);
		assertFalse(f.nav.active);
		assertEquals(TaskExecutionState.FAILED, f.executor.snapshot().state());
		assertTrue(f.events.getFirst().message().contains("acquisition_scope_left"));
	}

	@Test void noObservedTargetsFailsWithoutStartingBaritoneMiningOrExploration() {
		Fixture f = new Fixture();
		f.env.sources = List.of();
		f.tick(1);
		assertEquals(TaskExecutionState.FAILED, f.executor.snapshot().state());
		assertTrue(f.nav.goals.isEmpty());
	}

	@Test void completedBreakBudgetDoesNotHarvestAnExtraBlockWhileWaitingForDrops() {
		Fixture f = new Fixture();
		f.request = f.request.withMineGoalSatisfied(true);
		f.tick(1);
		assertEquals(TaskExecutionState.COMPLETED, f.executor.snapshot().state());
		assertEquals(0, f.env.breaks);
	}

	@Test void sessionPauseReleasesMovementAndDoesNotConsumeAttemptBudget() {
		Fixture f = new Fixture();
		f.tick(2);
		for (int i = 0; i < 3000; i++) f.executor.tick(SessionSnapshot.initial(), Optional.of(f.request));
		assertFalse(f.nav.active);
		f.tick(1);
		assertEquals(TaskExecutionState.RUNNING, f.executor.snapshot().state());
	}

	@Test void reflexWithdrawalDoesNotRestartAnUnproductiveApproachBudget() {
		Fixture f = new Fixture();
		f.tick(70);
		f.executor.tick(SessionSnapshot.initial(), Optional.empty());
		assertFalse(f.nav.active);
		f.tick(20);
		assertEquals(TaskExecutionState.FAILED, f.executor.snapshot().state());
		assertEquals(1, f.env.rejections);
	}

	@Test void requestedAreaIsFixedAndUsesHorizontalCircle() {
		AcquisitionConstraints constraints = new AcquisitionConstraints(pos(0,64,0), 10, 4, true);
		assertTrue(constraints.contains(pos(6,68,8)));
		assertFalse(constraints.contains(pos(8,64,8)));
		assertFalse(constraints.contains(pos(0,69,0)));
		assertSame(constraints, constraints.anchoredAt(pos(80,60,90)));
		assertThrows(IllegalArgumentException.class, () -> new AcquisitionConstraints(null, 0, 8, false));
	}

	private static GoalPosition pos(int x, int y, int z) { return new GoalPosition(x,y,z,true); }
	static final class Fixture {
		final FakeEnvironment env = new FakeEnvironment();
		final FakeNavigation nav = new FakeNavigation();
		final TargetAcquisitionTaskExecutor executor = new TargetAcquisitionTaskExecutor(nav, env);
		final List<TaskTerminalEvent> events = new ArrayList<>();
		WorldTaskRequest request = WorldTaskRequest.collectMine("mine", "job", new GoalSnapshot(GoalType.MINE_BLOCKS, null, null,
			new GoalMineSpec(List.of("log"), 1), 0, "test"));
		long tick;
		void tick(int n) {
			for (int i = 0; i < n; i++) executor.tick(new SessionSnapshot(SessionMode.SINGLEPLAYER_LAN_HOST,
				true,true,"minecraft:overworld",true,25565,++tick), Optional.of(request)).ifPresent(events::add);
		}
	}
	static final class FakeEnvironment implements Environment {
		GoalPosition position = pos(0,64,0);
		List<Candidate> sources = List.of(new Candidate(Kind.BLOCK,"log",pos(5,64,0),pos(4,64,0)));
		boolean interactable, inScope = true;
		int count, breaks, rejections;
		public GoalPosition position() { return position; }
		public int inventoryCount(GoalMineSpec s) { return count; }
		public boolean inScope(GoalPosition p, AcquisitionConstraints c, boolean standing) { return inScope && c.contains(p); }
		public List<Candidate> candidates(GoalMineSpec s, AcquisitionConstraints c, Set<String> rejected) {
			rejections = rejected.size();
			return sources.stream().filter(t -> !rejected.contains(t.key())).toList();
		}
		public boolean targetPresent(Candidate t) { return sources.contains(t); }
		public boolean canInteract(Candidate t) { return interactable; }
		public BreakResult breakTarget(Candidate t, GoalMineSpec s) { breaks++; sources = List.of(); return BreakResult.BROKEN; }
		public void cancelBreaking() {}
	}
	static final class FakeNavigation implements BaritoneFacade {
		boolean active;
		List<GoalPosition> goals = new ArrayList<>();
		public boolean isLoaded() { return true; }
		public void applySettings() {}
		public double walkOnWaterPenalty() { return 1; }
		public void setWalkOnWaterPenalty(double v) {}
		public void startFollow(String s) { fail("unexpected follow"); }
		public void startMine(GoalMineSpec s) { fail("System 1 must not delegate acquisition to Baritone"); }
		public void startNavigate(GoalPosition p) { goals.add(p); active = true; }
		public void startNavigateNear(GoalPosition p, int r) { fail("requires an exact work position"); }
		public boolean mineProcessActive() { return false; }
		public boolean processActive() { return active; }
		public boolean cancel() { active = false; return true; }
		public Optional<String> activeProcessName() { return Optional.empty(); }
		public Optional<Double> estimatedTicksToGoal() { return Optional.empty(); }
		public Optional<String> pollPathEvent() { return Optional.empty(); }
		public boolean navigationGoalReached(GoalPosition p) { return false; }
	}
}
