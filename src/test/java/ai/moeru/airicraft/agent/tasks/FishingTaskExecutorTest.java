package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FishingTaskExecutorTest {
	@Test void aNewCastAfterSessionPauseCanReelImmediatelyOnItsOwnBite() {
		Fixture f = new Fixture();
		f.tick(1);
		f.executor.tick(SessionSnapshot.initial(), Optional.of(f.request));
		assertEquals(1, f.env.reels);
		f.env.hook = null;
		f.executor.tick(SessionSnapshot.initial(), Optional.of(f.request));
		f.tick(1);
		assertEquals(2, f.env.casts);
		f.env.bite = true;
		f.tick(1);
		assertEquals(2, f.env.reels);
	}
	@Test void missingCastAcknowledgementNeverBecomesAReleaseJustBecauseTimePassed() {
		Fixture f = new Fixture();
		f.env.delayed = true;
		f.tick(1);
		for (int i = 0; i < 100; i++) f.cancel();
		assertFalse(f.executor.released());
		f.env.hook = UUID.randomUUID();
		f.cancel();
		assertEquals(1, f.env.reels);
		f.env.hook = null;
		f.cancel();
		assertTrue(f.executor.released());
	}

	@Test void biteReelsOnceButRetainsOwnershipUntilTheHookDisappears() {
		Fixture f = new Fixture();
		f.tick(1);
		f.env.bite = true;
		f.tick(3);
		assertEquals(1, f.env.reels);
		assertFalse(f.executor.released());
		assertTrue(f.events.isEmpty());
		f.env.hook = null;
		f.tick(25);
		assertTrue(f.executor.released());
		assertEquals(1, f.events.size());
		assertEquals(TaskExecutionState.COMPLETED, f.events.getFirst().terminalState());
		assertTrue(f.events.getFirst().message().contains("biteObserved=true"));
	}

	@Test void expiredCastReleasesTheHookBeforeCompletingWithoutClaimingACatch() {
		Fixture f = new Fixture();
		f.tick(105);
		assertTrue(f.events.isEmpty());
		assertFalse(f.executor.released());
		f.env.hook = null;
		f.tick(1);
		assertEquals(TaskExecutionState.COMPLETED, f.events.getFirst().terminalState());
		assertTrue(f.events.getFirst().message().contains("biteObserved=false"));
	}

	@Test void cancellationHandlesADelayedHookWithoutStartingAnotherCast() {
		Fixture f = new Fixture();
		f.env.delayed = true;
		f.tick(1);
		f.cancel();
		assertFalse(f.executor.released());
		f.env.hook = UUID.randomUUID();
		f.cancel();
		assertEquals(1, f.env.reels);
		assertFalse(f.executor.released());
		f.env.hook = null;
		f.cancel();
		assertTrue(f.executor.released());
		assertEquals(1, f.env.casts);
	}

	@Test void preexistingForeignHookIsNeverReeled() {
		Fixture f = new Fixture();
		f.env.hook = UUID.randomUUID();
		f.tick(2);
		assertEquals(TaskExecutionState.FAILED, f.events.getFirst().terminalState());
		assertEquals(0, f.env.casts);
		assertEquals(0, f.env.reels);
	}

	@Test void dispatcherKeepsThePreviousExecutorUntilItsReleaseIsConfirmed() {
		Fixture f = new Fixture();
		var other = new RecordingExecutor();
		var dispatcher = new DispatchingWorldTaskExecutor(new DispatchingWorldTaskExecutor.ExecutorSet(
			other, other, other, other, other, other, other, other, other, other, other, other, f.executor), null);
		dispatcher.tick(f.session(), Optional.of(f.request));
		var next = WorldTaskRequest.tendCrops("crop", "job", new CropTendingStepArgs("minecraft:wheat_seeds", 0, 64, 0, 0, 0));
		dispatcher.tick(f.session(), Optional.of(next));
		assertFalse(dispatcher.released());
		assertEquals(0, other.activeTicks);
		f.env.hook = null;
		dispatcher.tick(f.session(), Optional.of(next));
		assertEquals(1, other.activeTicks);
	}

	private static final class Fixture {
		final FakeEnvironment env = new FakeEnvironment();
		final FishingTaskExecutor executor = new FishingTaskExecutor(env);
		final WorldTaskRequest request = WorldTaskRequest.fishOnce("fish", "job", new FishOnceStepArgs(0, 63, 4, 100));
		final List<TaskTerminalEvent> events = new ArrayList<>();
		long ticks;
		SessionSnapshot session() { return new SessionSnapshot(SessionMode.SINGLEPLAYER_LAN_HOST, true, true, "minecraft:overworld", true, 25565, ++ticks); }
		void tick(int n) { for (int i = 0; i < n; i++) executor.tick(session(), Optional.of(request)).ifPresent(events::add); }
		void cancel() { executor.tick(session(), Optional.empty()); }
	}
	private static final class FakeEnvironment implements FishingTaskExecutor.Environment {
		UUID hook;
		boolean bite, delayed;
		int reels, casts;
		public String validate(FishOnceStepArgs args) { return hook == null ? null : "hook_already_present"; }
		public UUID hookId() { return hook; }
		public boolean biting() { return bite; }
		public boolean inWater() { return hook != null; }
		public boolean hookedEntity() { return false; }
		public boolean cast(FishOnceStepArgs args) { casts++; if (!delayed) hook = UUID.randomUUID(); return true; }
		public void reel(UUID ownedHook) { assertEquals(hook, ownedHook); reels++; }
	}
	private static final class RecordingExecutor implements WorldTaskExecutor {
		int activeTicks;
		public Optional<TaskTerminalEvent> tick(SessionSnapshot session, Optional<WorldTaskRequest> task) { if (task.isPresent()) activeTicks++; return Optional.empty(); }
		public TaskExecutionSnapshot snapshot() { return TaskExecutionSnapshot.idle(); }
		public void onWorldLeave() { }
		public void shutdown() { }
	}
}
