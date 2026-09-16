package ai.moeru.airicraft.os;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeActionRuntimeTest {
	@Test
	void rejectedRequestsCannotEvictAnActiveOrJustCompletedOperation() {
		var world = new ChestWorld();
		var runtime = new NativeActionRuntime(world, () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host", observation.epoch());
		var args = new JsonObject();
		args.addProperty("quantity", 1);
		var active = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", args);
		runtime.submit(active);
		for (int sequence = 2; sequence <= 260; sequence++) {
			var rejected = NativeActionRuntime.Request.create(lease, sequence, observation.captureId(), "transfer_container", args);
			assertEquals("player_owned", runtime.submit(rejected).reason());
		}
		assertEquals(false, runtime.inspect(active.id()).released());
		runtime.tick(); runtime.tick();
		assertTrue(runtime.inspect(active.id()).released());
		var oldest = new NativeActionRuntime.Id(lease.epoch(), lease.generation(), 2);
		assertEquals("outcome_unknown", assertThrows(NativeActionRuntime.Rejected.class, () -> runtime.inspect(oldest)).code());
	}

	@Test
	void aRejectedAdmissionRemainsQueryableAfterItsResponseIsLost() {
		var clock = new AtomicLong();
		var runtime = new NativeActionRuntime(new ChestWorld(), clock::get);
		var observation = runtime.observe();
		var lease = runtime.acquire("host", observation.epoch());
		var args = new JsonObject();
		args.addProperty("quantity", 6);
		var request = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", args);
		clock.set(2_000_000_000L);
		try { runtime.submit(request); } catch (NativeActionRuntime.Rejected lostReply) { /* Simulate losing the admission response. */ }
		var rejected = runtime.inspect(request.id());
		assertEquals(NativeActionRuntime.State.FAILED, rejected.state());
		assertEquals("admission_rejected", rejected.phase());
		assertEquals("observation_stale", rejected.reason());
		assertTrue(rejected.released());
		assertEquals(true, rejected.effects().get("accountingComplete"));
		assertEquals(rejected, runtime.submit(request));
		assertEquals(null, runtime.authority().active());
		assertEquals(0, runtime.observe().facts().get("carriedWheat").getAsInt());
		runtime.submit(NativeActionRuntime.Request.create(lease, 2, runtime.observe().captureId(), "transfer_container", args));
		runtime.tick(); runtime.tick();
		assertEquals(6, runtime.observe().facts().get("carriedWheat").getAsInt());
	}

	@Test
	void expiredAuthorityStillIdentifiesTheGenerationOfItsAdmissionHighWaterMark() {
		var clock = new AtomicLong();
		var runtime = new NativeActionRuntime(new ChestWorld(), clock::get);
		var lease = runtime.acquire("host-before-crash", runtime.observe().epoch());
		clock.set(6_000_000_000L);
		var authority = runtime.authority();
		assertEquals(null, authority.lease());
		assertEquals(lease.generation(), authority.generation());
		assertEquals(0, authority.admissionSequence());
		var next = runtime.acquire("replacement-host", authority.epoch());
		assertEquals(next.generation(), runtime.authority().generation());
		assertEquals(lease.generation() + 1, next.generation());
	}

	@Test
	void aLifecycleCallbackFencesQueuedEffectsBeforeTheNextClientTick() {
		var world = new ChestWorld();
		world.deferEffects = true;
		world.cleanupAllowed = false;
		var runtime = new NativeActionRuntime(world, () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		var args = new JsonObject();
		args.addProperty("quantity", 6);
		var request = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", args);
		runtime.submit(request);
		runtime.tick();
		runtime.interrupt("runtime_shutdown");
		world.queued.run();
		assertEquals(0, runtime.observe().facts().get("carriedWheat").getAsInt());
		assertEquals("runtime_shutdown", runtime.inspect(request.id()).reason());
		assertEquals(false, runtime.inspect(request.id()).released());
		assertEquals("stale_fence", assertThrows(NativeActionRuntime.Rejected.class, () -> runtime.heartbeat(lease)).code());
	}
	@Test
	void eventHistoryReportsLostCoverageWithoutLosingRetainedOutcomes() {
		var runtime = new NativeActionRuntime(new ChestWorld(), () -> 0L);
		var lease = runtime.acquire("host-a", runtime.observe().epoch());
		NativeActionRuntime.Request last = null;
		for (int sequence = 1; sequence <= 180; sequence++) {
			var args = new JsonObject();
			args.addProperty("quantity", 1);
			last = NativeActionRuntime.Request.create(lease, sequence, runtime.observe().captureId(), "transfer_container", args);
			runtime.submit(last);
			runtime.tick();
			runtime.tick();
		}
		var first = runtime.history(0, 16);
		assertTrue(first.gap());
		assertEquals(16, first.events().size());
		assertEquals(512, first.latestSeqNo() - first.oldestSeqNo() + 1);
		var next = runtime.history(first.nextSeqNo(), 16);
		assertEquals(false, next.gap());
		assertEquals(first.nextSeqNo() + 1, next.events().getFirst().seqNo());
		assertTrue(runtime.inspect(last.id()).released());
		var terminal = runtime.history(first.latestSeqNo() - 1, 16).events().getFirst();
		assertEquals(runtime.inspect(last.id()), terminal.receipt());
		long beforeReads = first.latestSeqNo();
		runtime.inspect(last.id());
		runtime.observe();
		assertEquals(beforeReads, runtime.history(beforeReads, 16).latestSeqNo());
	}

	@Test
	void repeatedSubmissionTransfersOnlyOnceAndKeepsAQueryableOutcome() {
		var world = new ChestWorld();
		var runtime = new NativeActionRuntime(world, () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		var arguments = new JsonObject();
		arguments.addProperty("quantity", 6);
		var request = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", arguments);

		var admitted = runtime.submit(request);
		assertEquals(NativeActionRuntime.State.ACCEPTED, admitted.state());
		assertEquals(admitted, runtime.submit(request));
		runtime.tick();
		runtime.tick();

		assertEquals(6, runtime.observe().facts().get("carriedWheat").getAsInt());
		var completed = runtime.inspect(request.id());
		assertEquals(NativeActionRuntime.State.SUCCEEDED, completed.state());
		assertTrue(completed.released());
		assertEquals(completed, runtime.submit(request));
		assertEquals(6, runtime.observe().facts().get("carriedWheat").getAsInt());
	}

	@Test
	void evictedReceiptCannotTurnAnOldSubmissionIntoAnotherTransfer() {
		var runtime = new NativeActionRuntime(new ChestWorld(), () -> 0L);
		var lease = runtime.acquire("host-a", runtime.observe().epoch());
		NativeActionRuntime.Request first = null;
		for (int sequence = 1; sequence <= 257; sequence++) {
			var arguments = new JsonObject();
			arguments.addProperty("quantity", 1);
			var request = NativeActionRuntime.Request.create(lease, sequence, runtime.observe().captureId(), "transfer_container", arguments);
			if (first == null) first = request;
			runtime.submit(request);
			runtime.tick();
			runtime.tick();
		}
		var forgotten = first;
		assertEquals("outcome_unknown", assertThrows(NativeActionRuntime.Rejected.class,
			() -> runtime.submit(forgotten)).code());
		assertEquals("outcome_unknown", assertThrows(NativeActionRuntime.Rejected.class,
			() -> runtime.inspect(forgotten.id())).code());
		assertEquals(257, runtime.observe().facts().get("carriedWheat").getAsInt());
	}

	@Test
	void expiredHostCannotRenewAndAnotherHostWaitsForPhysicalRelease() {
		var clock = new AtomicLong();
		var world = new ChestWorld();
		world.cleanupAllowed = false;
		var runtime = new NativeActionRuntime(world, clock::get);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		var arguments = new JsonObject();
		arguments.addProperty("quantity", 6);
		var request = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", arguments);
		runtime.submit(request);
		runtime.tick();
		clock.set(5_000_000_000L);
		runtime.tick();

		assertEquals(NativeActionRuntime.State.RECONCILING, runtime.inspect(request.id()).state());
		assertEquals("stale_fence", assertThrows(NativeActionRuntime.Rejected.class, () -> runtime.heartbeat(lease)).code());
		assertEquals("unresolved_release", assertThrows(NativeActionRuntime.Rejected.class,
			() -> runtime.acquire("host-b", observation.epoch())).code());
		world.cleanupAllowed = true;
		runtime.tick();
		assertEquals(NativeActionRuntime.State.CANCELLED, runtime.inspect(request.id()).state());
		assertTrue(runtime.inspect(request.id()).released());
		assertEquals(6, runtime.observe().facts().get("carriedWheat").getAsInt());
		var replacement = runtime.acquire("host-b", observation.epoch());
		assertTrue(replacement.generation() > lease.generation());
		assertEquals("stale_fence", assertThrows(NativeActionRuntime.Rejected.class, () -> runtime.submit(request)).code());
	}

	@Test
	void cancellationBeforeTheFirstTickDoesNotTransferItemsAndCannotChangeASettledOutcome() {
		var runtime = new NativeActionRuntime(new ChestWorld(), () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		var arguments = new JsonObject();
		arguments.addProperty("quantity", 6);
		var request = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", arguments);
		runtime.submit(request);
		assertEquals(NativeActionRuntime.State.RECONCILING, runtime.cancel(lease, request.id()).state());
		runtime.tick();
		var outcome = runtime.inspect(request.id());
		assertEquals(NativeActionRuntime.State.CANCELLED, outcome.state());
		assertTrue(outcome.released());
		assertEquals(0, runtime.observe().facts().get("carriedWheat").getAsInt());
		assertEquals(outcome, runtime.cancel(lease, request.id()));
		assertEquals(outcome, runtime.submit(request));
	}

	@Test
	void reloadingTheSameSaveRevokesOldAdmissionsAndObservationAuthority() {
		var world = new ChestWorld();
		var runtime = new NativeActionRuntime(world, () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		var arguments = new JsonObject();
		arguments.addProperty("quantity", 6);
		var request = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", arguments);
		runtime.submit(request);
		world.loadId = "load-b";
		var newWorld = runtime.observe();
		assertNotEquals(observation.epoch(), newWorld.epoch());
		assertEquals("stale_fence", assertThrows(NativeActionRuntime.Rejected.class, () -> runtime.submit(request)).code());
		runtime.tick();
		assertEquals(0, runtime.observe().facts().get("carriedWheat").getAsInt());
		var replacement = runtime.acquire("host-b", newWorld.epoch());
		var staleBasis = NativeActionRuntime.Request.create(replacement, 1, observation.captureId(), "transfer_container", arguments);
		assertEquals("observation_unknown", runtime.submit(staleBasis).reason());
	}

	@Test
	void anObservationExpiresByWallTimeEvenWhenTheWorldIsPaused() {
		var clock = new AtomicLong();
		var runtime = new NativeActionRuntime(new ChestWorld(), clock::get);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		var arguments = new JsonObject();
		arguments.addProperty("quantity", 6);
		var request = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", arguments);
		clock.set(2_000_000_000L);
		assertEquals("observation_stale", runtime.submit(request).reason());
		assertEquals(0, runtime.observe().facts().get("carriedWheat").getAsInt());
	}

	@Test
	void aCompletedTransferRequiresANewObservationEvenWithinTheFreshnessWindow() {
		var runtime = new NativeActionRuntime(new ChestWorld(), () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		var arguments = new JsonObject();
		arguments.addProperty("quantity", 6);
		runtime.submit(NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", arguments));
		runtime.tick();
		runtime.tick();
		var stale = NativeActionRuntime.Request.create(lease, 2, observation.captureId(), "transfer_container", arguments);
		assertEquals("observation_stale", runtime.submit(stale).reason());
		var fresh = NativeActionRuntime.Request.create(lease, 3, runtime.observe().captureId(), "transfer_container", arguments);
		runtime.submit(fresh);
		runtime.tick();
		runtime.tick();
		assertEquals(12, runtime.observe().facts().get("carriedWheat").getAsInt());
	}

	@Test
	void reflexTakeoverRevokesTheHostAndDefersCleanupUntilHandback() {
		var world = new ChestWorld();
		var runtime = new NativeActionRuntime(world, () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		var arguments = new JsonObject();
		arguments.addProperty("quantity", 6);
		var request = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", arguments);
		runtime.submit(request);
		world.reflex = true;
		runtime.tick();
		assertEquals(0, runtime.observe().facts().get("carriedWheat").getAsInt());
		assertEquals(NativeActionRuntime.State.RECONCILING, runtime.inspect(request.id()).state());
		assertEquals("stale_fence", assertThrows(NativeActionRuntime.Rejected.class, () -> runtime.heartbeat(lease)).code());
		world.reflex = false;
		runtime.tick();
		assertEquals(NativeActionRuntime.State.CANCELLED, runtime.inspect(request.id()).state());
		assertTrue(runtime.inspect(request.id()).released());
		assertEquals("reflex_takeover", runtime.inspect(request.id()).reason());
	}

	@Test
	void anExecutorExceptionKeepsOwnershipUntilCleanupAndCannotBecomeSuccess() {
		var world = new ChestWorld();
		world.failAfterMove = true;
		world.cleanupAllowed = false;
		var runtime = new NativeActionRuntime(world, () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		var arguments = new JsonObject();
		arguments.addProperty("quantity", 6);
		var request = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", arguments);
		runtime.submit(request);
		runtime.tick();
		assertEquals(NativeActionRuntime.State.RECONCILING, runtime.inspect(request.id()).state());
		assertEquals("player_owned", assertThrows(NativeActionRuntime.Rejected.class,
			() -> runtime.acquire("host-b", observation.epoch())).code());
		runtime.cancel(lease, request.id());
		world.cleanupAllowed = true;
		runtime.tick();
		assertEquals(NativeActionRuntime.State.FAILED, runtime.inspect(request.id()).state());
		assertEquals("executor_exception", runtime.inspect(request.id()).reason());
		assertEquals(6, runtime.inspect(request.id()).effects().get("transferred"));
		assertTrue(runtime.inspect(request.id()).released());
	}

	@Test
	void aHostCannotAcquireADeadOrOtherwiseControlledPlayer() {
		var world = new ChestWorld();
		var runtime = new NativeActionRuntime(world, () -> 0L);
		String epoch = runtime.observe().epoch();
		world.alive = false;
		assertEquals("player_unavailable", assertThrows(NativeActionRuntime.Rejected.class, () -> runtime.acquire("host-a", epoch)).code());
		world.alive = true;
		world.busy = true;
		assertEquals("controller_busy", assertThrows(NativeActionRuntime.Rejected.class, () -> runtime.acquire("host-a", epoch)).code());
		world.busy = false;
		world.reflex = true;
		assertEquals("reflex_active", assertThrows(NativeActionRuntime.Rejected.class, () -> runtime.acquire("host-a", epoch)).code());
		world.reflex = false;
		var lease = runtime.acquire("host-a", epoch);
		var arguments = new JsonObject();
		arguments.addProperty("quantity", 6);
		var request = NativeActionRuntime.Request.create(lease, 1, runtime.observe().captureId(), "transfer_container", arguments);
		runtime.submit(request);
		world.alive = false;
		runtime.tick();
		assertEquals(0, runtime.observe().facts().get("carriedWheat").getAsInt());
		assertEquals("player_unavailable", runtime.inspect(request.id()).reason());
	}

	@Test
	void explicitHostReleaseKeepsOrdinaryActionsFencedUntilCleanupCompletes() {
		var world = new ChestWorld();
		world.cleanupAllowed = false;
		var runtime = new NativeActionRuntime(world, () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		var arguments = new JsonObject();
		arguments.addProperty("quantity", 6);
		runtime.submit(NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", arguments));
		runtime.release(lease);
		assertTrue(runtime.blocksOrdinaryActions());
		runtime.tick();
		assertTrue(runtime.blocksOrdinaryActions());
		world.cleanupAllowed = true;
		runtime.tick();
		assertEquals(false, runtime.blocksOrdinaryActions());
		assertEquals("stale_fence", assertThrows(NativeActionRuntime.Rejected.class, () -> runtime.heartbeat(lease)).code());
	}

	@Test
	void anExecutorFailureIsNotTerminalUntilItsReleaseIsConfirmed() {
		var world = new ChestWorld();
		world.reportFailure = true;
		world.cleanupAllowed = false;
		var runtime = new NativeActionRuntime(world, () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		var arguments = new JsonObject();
		arguments.addProperty("quantity", 6);
		var request = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", arguments);
		runtime.submit(request);
		runtime.tick();
		assertEquals(NativeActionRuntime.State.RECONCILING, runtime.inspect(request.id()).state());
		assertEquals(request.id(), runtime.authority().active().id());
		runtime.cancel(lease, request.id());
		world.cleanupAllowed = true;
		runtime.tick();
		assertEquals(NativeActionRuntime.State.FAILED, runtime.inspect(request.id()).state());
		assertEquals("transfer_failed", runtime.inspect(request.id()).reason());
		assertEquals(null, runtime.authority().active());
	}

	@Test
	void externalControlFencesTheHostBeforeItsNextEffect() {
		var world = new ChestWorld();
		var runtime = new NativeActionRuntime(world, () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		var arguments = new JsonObject();
		arguments.addProperty("quantity", 6);
		var request = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", arguments);
		runtime.submit(request);
		world.busy = true;
		runtime.tick();
		assertEquals(0, runtime.observe().facts().get("carriedWheat").getAsInt());
		assertEquals("stale_fence", assertThrows(NativeActionRuntime.Rejected.class, () -> runtime.heartbeat(lease)).code());
		assertEquals(false, runtime.inspect(request.id()).released());
		world.busy = false;
		runtime.tick();
		assertTrue(runtime.inspect(request.id()).released());
		assertEquals("external_controller", runtime.inspect(request.id()).reason());
	}

	@Test
	void anAlreadyQueuedEffectChecksTheLeaseAgainAtApplication() {
		var clock = new AtomicLong();
		var world = new ChestWorld();
		world.deferEffects = true;
		var runtime = new NativeActionRuntime(world, clock::get);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		var arguments = new JsonObject();
		arguments.addProperty("quantity", 6);
		runtime.submit(NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", arguments));
		runtime.tick();
		clock.set(5_000_000_000L);
		world.queued.run(); // The external action thread runs before the next native client tick.
		assertEquals(0, runtime.observe().facts().get("carriedWheat").getAsInt());
	}

	@Test
	void availabilityEvidenceIsFencedAtEveryAuthorityAndActivityTransition() {
		var clock = new AtomicLong();
		var world = new ChestWorld();
		var runtime = new NativeActionRuntime(world, clock::get);
		var observation = runtime.observe();
		assertNull(world.availabilityLease);
		var lease = runtime.acquire("host-a", observation.epoch());
		assertEquals(lease, world.availabilityLease);
		assertTrue(world.availabilityIdle);
		clock.set(1_000_000_000L);
		runtime.heartbeat(lease);
		assertEquals(1_000_000_000L, world.availabilityHeartbeat);
		var arguments = new JsonObject(); arguments.addProperty("quantity", 1);
		var request = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", arguments);
		runtime.submit(request);
		assertFalse(world.availabilityIdle);
		runtime.tick(); runtime.tick();
		assertTrue(world.availabilityIdle);
		runtime.interrupt("reflex_takeover");
		assertNull(world.availabilityLease);
		lease = runtime.acquire("host-a", observation.epoch());
		assertEquals(lease, world.availabilityLease);
		clock.set(6_000_000_000L);
		runtime.tick();
		assertNull(world.availabilityLease);
	}

	/** A deterministic stand-in for Minecraft, the system outside the native action interface. */
	private static final class ChestWorld implements NativeActionRuntime.Port {
		private int carried;
		private boolean cleanupAllowed = true;
		private String loadId = "load-a";
		private boolean reflex;
		private boolean failAfterMove;
		private boolean reportFailure;
		private boolean deferEffects;
		private Runnable queued;
		private boolean alive = true;
		private boolean busy;
		private NativeActionRuntime.Lease availabilityLease;
		private boolean availabilityIdle;
		private long availabilityHeartbeat;
		@Override public void availability(NativeActionRuntime.AvailabilityGate gate) {
			availabilityLease = gate.lease(); availabilityIdle = gate.idle(); availabilityHeartbeat = gate.heartbeatAtNanos();
		}
		@Override public NativeActionRuntime.World world() {
			return new NativeActionRuntime.World("save-a", "minecraft:overworld", loadId, alive, busy, reflex);
		}
		@Override public Set<String> operations() { return Set.of("transfer_container"); }
		@Override public JsonObject observe() {
			var facts = new JsonObject();
			facts.addProperty("carriedWheat", carried);
			return facts;
		}
		@Override public NativeActionRuntime.Operation prepare(String name, JsonObject arguments, JsonObject basis, NativeActionRuntime.EffectPermit permit) {
			int quantity = arguments.get("quantity").getAsInt();
			return new NativeActionRuntime.Operation() {
				private boolean moved;
				@Override public NativeActionRuntime.Progress tick(NativeActionRuntime.Permission permission) {
					if (permission != NativeActionRuntime.Permission.RUN) {
						return new NativeActionRuntime.Progress(NativeActionRuntime.State.RECONCILING,
							cleanupAllowed && permission == NativeActionRuntime.Permission.CLEANUP,
							"closing", "", Map.of("transferred", moved ? quantity : 0));
					}
					if (!moved) {
						if (deferEffects) {
							queued = () -> { if (permit.allows(NativeActionRuntime.Permission.RUN)) carried += quantity; };
							return NativeActionRuntime.Progress.running("queued", Map.of());
						}
						carried += quantity;
						moved = true;
						if (failAfterMove) throw new IllegalStateException("lost transfer response");
						if (reportFailure) return new NativeActionRuntime.Progress(NativeActionRuntime.State.FAILED, false, "closing", "transfer_failed", Map.of("transferred", quantity));
						return NativeActionRuntime.Progress.running("verifying", Map.of("transferred", quantity));
					}
					return NativeActionRuntime.Progress.success(Map.of("transferred", quantity));
				}
			};
		}
	}
}
