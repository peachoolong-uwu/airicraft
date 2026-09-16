package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.os.NativeActionRuntime;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class NativeContainerActionsTest {
	@Test
	void contextExitWatchdogStillReportsFailureWhileAReflexOwnsThePlayer() {
		var clock = new java.util.concurrent.atomic.AtomicLong();
		var chest = new Chest();
		chest.delayConfirmation = false;
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, clock::get), clock::get);
		var observation = runtime.observe();
		var lease = runtime.acquire("host", observation.epoch());
		var binding = com.google.gson.JsonParser.parseString("{\"windowId\":\"window-a\",\"syncId\":7}").getAsJsonObject();
		var context = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "retain_container", binding);
		runtime.submit(context); runtime.tick();
		chest.deferClose = true;
		runtime.cancel(lease, context.id());
		runtime.tick(); runtime.tick();
		chest.reflex = true;
		runtime.interrupt("reflex_takeover");
		chest.queuedClose.run();
		clock.set(30_000_000_000L);
		runtime.tick();
		assertEquals("context_exit_timeout", runtime.inspect(context.id()).effects().get("cleanupFailure"));
		assertFalse(runtime.inspect(context.id()).released());
		assertTrue(runtime.blocksOrdinaryActions());
		assertTrue(chest.open);
		assertEquals(0, chest.closures, "watchdog reporting must not authorize an effect during the hold");
		chest.reflex = false;
		chest.deferClose = false;
		for (int i = 0; i < 20 && !runtime.inspect(context.id()).released(); i++) runtime.tick();
		assertTrue(runtime.inspect(context.id()).released());
		assertEquals(NativeActionRuntime.State.FAILED, runtime.inspect(context.id()).state());
		assertEquals("context_exit_timeout", runtime.inspect(context.id()).effects().get("cleanupFailure"));
		assertEquals(1, chest.closures);
		assertFalse(runtime.blocksOrdinaryActions());
	}

	@Test
	void contextCancellationPreservesAChildFailureThatFinishesCleanupLater() {
		var chest = new Chest();
		chest.delayConfirmation = false;
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, () -> 0L), () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host", observation.epoch());
		var binding = com.google.gson.JsonParser.parseString("{\"windowId\":\"window-a\",\"syncId\":7}").getAsJsonObject();
		var context = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "retain_container", binding);
		runtime.submit(context); runtime.tick();
		var args = transfer(1);
		args.addProperty("contextId", (String) runtime.inspect(context.id()).effects().get("contextId"));
		var request = NativeActionRuntime.Request.create(lease, 2, runtime.observe().captureId(), "transfer_container", args);
		runtime.submit(request);
		chest.rejectClick = true;
		for (int i = 0; i < 3; i++) runtime.tick();
		assertEquals("container_changed", runtime.inspect(request.id()).reason());
		assertFalse(runtime.inspect(request.id()).released());
		runtime.cancel(lease, context.id());
		chest.rejectClick = false;
		for (int i = 0; i < 20 && !runtime.inspect(context.id()).released(); i++) runtime.tick();
		assertEquals(NativeActionRuntime.State.FAILED, runtime.inspect(request.id()).state());
		assertTrue(runtime.inspect(context.id()).released());
		assertEquals("cancelled", runtime.inspect(context.id()).reason(), "the first stop reason remains available");
		assertEquals(NativeActionRuntime.State.FAILED, runtime.inspect(context.id()).state());
		assertEquals(20, chest.stored);
		assertEquals(0, chest.carried);
	}

	@Test
	void contextEntryTimeoutDoesNotClaimTheUnconfirmedWindowWasReleased() {
		var clock = new java.util.concurrent.atomic.AtomicLong();
		var chest = new Chest();
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, clock::get), clock::get);
		var observation = runtime.observe();
		var lease = runtime.acquire("host", observation.epoch());
		var binding = com.google.gson.JsonParser.parseString("{\"windowId\":\"window-a\",\"syncId\":7}").getAsJsonObject();
		var context = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "retain_container", binding);
		runtime.submit(context); runtime.tick();
		for (int second = 1; second <= 30; second++) {
			clock.set(second * 1_000_000_000L);
			runtime.heartbeat(lease);
			runtime.tick();
		}
		assertFalse(runtime.inspect(context.id()).released());
		assertEquals("context_entry_timeout", runtime.inspect(context.id()).reason());
		assertEquals(false, runtime.inspect(context.id()).effects().get("contextReady"));
		chest.confirmation.complete(chest.capture());
		chest.delayConfirmation = false;
		for (int i = 0; i < 20 && !runtime.inspect(context.id()).released(); i++) runtime.tick();
		assertTrue(runtime.inspect(context.id()).released());
		assertEquals(NativeActionRuntime.State.FAILED, runtime.inspect(context.id()).state());
		assertEquals(20, chest.stored);
	}

	@Test
	void receiptPressureCannotEvictARetainedContextOrItsCurrentOperation() {
		var chest = new Chest();
		chest.delayConfirmation = false;
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, () -> 0L), () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host", observation.epoch());
		var binding = com.google.gson.JsonParser.parseString("{\"windowId\":\"window-a\",\"syncId\":7}").getAsJsonObject();
		var context = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "retain_container", binding);
		runtime.submit(context); runtime.tick();
		var args = transfer(1);
		args.addProperty("contextId", (String) runtime.inspect(context.id()).effects().get("contextId"));
		var request = NativeActionRuntime.Request.create(lease, 2, runtime.observe().captureId(), "transfer_container", args);
		runtime.submit(request);
		for (int sequence = 3; sequence < 270; sequence++)
			assertEquals("player_owned", runtime.submit(NativeActionRuntime.Request.create(lease, sequence,
				runtime.observe().captureId(), "transfer_container", args)).reason());
		assertFalse(runtime.inspect(context.id()).released());
		assertFalse(runtime.inspect(request.id()).released());
		assertEquals("outcome_unknown", assertThrows(NativeActionRuntime.Rejected.class,
			() -> runtime.inspect(new NativeActionRuntime.Id(lease.epoch(), lease.generation(), 3))).code());
		for (int i = 0; i < 20 && !runtime.inspect(request.id()).released(); i++) runtime.tick();
		assertTrue(runtime.inspect(request.id()).released());
		runtime.cancel(lease, context.id());
		for (int i = 0; i < 20 && !runtime.inspect(context.id()).released(); i++) runtime.tick();
		assertTrue(runtime.inspect(context.id()).released());
		assertEquals(1, chest.carried);
	}

	@Test
	void anUnexpectedContextCleanupExceptionRemainsVisibleAfterRecovery() {
		var chest = new Chest();
		chest.delayConfirmation = false;
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, () -> 0L), () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host", observation.epoch());
		var binding = com.google.gson.JsonParser.parseString("{\"windowId\":\"window-a\",\"syncId\":7}").getAsJsonObject();
		var context = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "retain_container", binding);
		runtime.submit(context); runtime.tick();
		chest.failClose = true;
		runtime.cancel(lease, context.id());
		runtime.tick(); runtime.tick();
		assertFalse(runtime.inspect(context.id()).released());
		assertEquals("executor_exception", runtime.inspect(context.id()).effects().get("cleanupFailure"));
		chest.failClose = false;
		for (int i = 0; i < 20 && !runtime.inspect(context.id()).released(); i++) runtime.tick();
		assertTrue(runtime.inspect(context.id()).released());
		assertEquals(NativeActionRuntime.State.FAILED, runtime.inspect(context.id()).state());
		assertEquals("executor_exception", runtime.inspect(context.id()).effects().get("cleanupFailure"));
		assertEquals(1, chest.closures);
	}

	@Test
	void reflexTakeoverFencesAQueuedContextCloseAndCleanupResumesAfterHandback() {
		var chest = new Chest();
		chest.delayConfirmation = false;
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, () -> 0L), () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host", observation.epoch());
		var binding = com.google.gson.JsonParser.parseString("{\"windowId\":\"window-a\",\"syncId\":7}").getAsJsonObject();
		var context = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "retain_container", binding);
		runtime.submit(context); runtime.tick();
		chest.deferClose = true;
		runtime.cancel(lease, context.id());
		runtime.tick(); runtime.tick();
		chest.reflex = true;
		runtime.interrupt("reflex_takeover");
		chest.queuedClose.run();
		assertTrue(chest.open, "already-queued cleanup must obey immediate revocation");
		runtime.tick();
		assertFalse(runtime.inspect(context.id()).released());
		assertTrue(runtime.blocksOrdinaryActions());
		chest.reflex = false;
		chest.deferClose = false;
		for (int i = 0; i < 20 && !runtime.inspect(context.id()).released(); i++) runtime.tick();
		var completed = runtime.inspect(context.id());
		assertTrue(completed.released());
		assertEquals(NativeActionRuntime.State.CANCELLED, completed.state());
		assertFalse(completed.effects().containsKey("cleanupFailure"));
		assertEquals(1, chest.closures);
		assertFalse(runtime.blocksOrdinaryActions());
	}

	@Test
	void anExternallyClosedIdleContextDoesNotInventUnknownItemEffects() {
		var chest = new Chest();
		chest.delayConfirmation = false;
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, () -> 0L), () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host", observation.epoch());
		var binding = com.google.gson.JsonParser.parseString("{\"windowId\":\"window-a\",\"syncId\":7}").getAsJsonObject();
		var context = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "retain_container", binding);
		runtime.submit(context);
		runtime.tick();
		chest.open = false;
		for (int i = 0; i < 20 && !runtime.inspect(context.id()).released(); i++) runtime.tick();
		var result = runtime.inspect(context.id());
		assertTrue(result.released());
		assertEquals(NativeActionRuntime.State.FAILED, result.state());
		assertEquals("context_changed", result.reason());
		assertEquals(true, result.effects().get("accountingComplete"));
		assertEquals(20, chest.stored);
		assertEquals(0, chest.carried);
		assertEquals(0, chest.closures);
		assertEquals(1, chest.releases);
	}

	@Test
	void aRetainedContextRequiresItsExactIdentityAndConfirmedSetupForEveryOperation() {
		var chest = new Chest();
		chest.delayConfirmation = false;
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, () -> 0L), () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host", observation.epoch());
		var binding = com.google.gson.JsonParser.parseString("{\"windowId\":\"window-a\",\"syncId\":7}").getAsJsonObject();
		var context = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "retain_container", binding);
		String id = (String) runtime.submit(context).effects().get("contextId");
		var args = transfer(1);
		args.addProperty("contextId", id);
		var tooEarly = NativeActionRuntime.Request.create(lease, 2, runtime.observe().captureId(), "transfer_container", args);
		assertEquals("context_not_ready", runtime.submit(tooEarly).reason());
		assertEquals(0, chest.retentions, "admission performs no context entry effects");
		runtime.tick();
		var missing = NativeActionRuntime.Request.create(lease, 3, runtime.observe().captureId(), "transfer_container", transfer(1));
		assertEquals("context_required", runtime.submit(missing).reason());
		args.addProperty("contextId", "different-visit");
		var wrong = NativeActionRuntime.Request.create(lease, 4, runtime.observe().captureId(), "transfer_container", args);
		assertEquals("context_required", runtime.submit(wrong).reason());
		args.addProperty("contextId", id);
		runtime.cancel(lease, context.id());
		var closing = NativeActionRuntime.Request.create(lease, 5, runtime.observe().captureId(), "transfer_container", args);
		assertEquals("context_not_ready", runtime.submit(closing).reason());
		for (int i = 0; i < 20 && !runtime.inspect(context.id()).released(); i++) runtime.tick();
		var stale = NativeActionRuntime.Request.create(lease, 6, runtime.observe().captureId(), "transfer_container", args);
		assertEquals("context_unknown", runtime.submit(stale).reason());
		assertEquals(runtime.inspect(stale.id()), runtime.submit(stale), "a rejected retry cannot create another admission");
		assertEquals(20, chest.stored);
		assertEquals(0, chest.carried);
	}

	@Test
	void hostExpiryDrainsTheCurrentTransferBeforeClosingItsRetainedContext() {
		var clock = new java.util.concurrent.atomic.AtomicLong();
		var chest = new Chest();
		chest.delayConfirmation = false;
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, clock::get), clock::get);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-before-crash", observation.epoch());
		var binding = com.google.gson.JsonParser.parseString("{\"windowId\":\"window-a\",\"syncId\":7}").getAsJsonObject();
		var context = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "retain_container", binding);
		runtime.submit(context);
		runtime.tick();
		var args = transfer(6);
		args.addProperty("contextId", (String) runtime.inspect(context.id()).effects().get("contextId"));
		var transfer = NativeActionRuntime.Request.create(lease, 2, runtime.observe().captureId(), "transfer_container", args);
		runtime.submit(transfer);
		for (int i = 0; i < 20 && chest.carried < 2; i++) runtime.tick();
		assertEquals(2, chest.carried);
		chest.closeAcknowledged = false;
		clock.set(5_000_000_000L);
		for (int i = 0; i < 20; i++) runtime.tick();
		var partial = runtime.inspect(transfer.id());
		assertTrue(partial.released());
		assertEquals(NativeActionRuntime.State.CANCELLED, partial.state());
		assertEquals("lease_expired", partial.reason());
		assertEquals(2, partial.effects().get("transferred"));
		assertEquals(18, chest.stored);
		assertEquals(0, chest.cursor);
		assertFalse(runtime.inspect(context.id()).released());
		assertNotNull(runtime.authority().context());
		assertEquals("unresolved_release", assertThrows(NativeActionRuntime.Rejected.class,
			() -> runtime.acquire("replacement", observation.epoch())).code());
		assertTrue(runtime.blocksOrdinaryActions());
		chest.closeAcknowledged = true;
		for (int i = 0; i < 10 && !runtime.inspect(context.id()).released(); i++) runtime.tick();
		assertTrue(runtime.inspect(context.id()).released());
		assertNull(runtime.authority().context());
		assertEquals(1, chest.closures);
		var replacement = runtime.acquire("replacement", observation.epoch());
		assertTrue(replacement.generation() > lease.generation());
		assertEquals("stale_fence", assertThrows(NativeActionRuntime.Rejected.class, () -> runtime.submit(transfer)).code());
	}

	@Test
	void aContextExitTimeoutRemainsAnExplicitFailureUntilTheOwnedWindowIsReleased() {
		var clock = new java.util.concurrent.atomic.AtomicLong();
		var chest = new Chest();
		chest.delayConfirmation = false;
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, clock::get), clock::get);
		var observation = runtime.observe();
		var lease = runtime.acquire("host", observation.epoch());
		var binding = com.google.gson.JsonParser.parseString("{\"windowId\":\"window-a\",\"syncId\":7}").getAsJsonObject();
		var context = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "retain_container", binding);
		runtime.submit(context);
		runtime.tick();
		assertEquals("context_ready", runtime.inspect(context.id()).phase());
		chest.deferClose = true;
		runtime.cancel(lease, context.id());
		runtime.tick(); runtime.tick();
		for (int second = 1; second <= 30; second++) {
			clock.set(second * 1_000_000_000L);
			runtime.heartbeat(lease);
			runtime.tick();
		}
		var unresolved = runtime.inspect(context.id());
		assertFalse(unresolved.released());
		assertTrue(runtime.blocksOrdinaryActions());
		assertEquals("context_exit_timeout", unresolved.effects().get("cleanupFailure"));
		chest.queuedClose.run();
		runtime.tick();
		var completed = runtime.inspect(context.id());
		assertTrue(completed.released());
		assertEquals(NativeActionRuntime.State.FAILED, completed.state());
		assertEquals("context_exit_timeout", completed.effects().get("cleanupFailure"));
	}

	@Test
	void independentBoundedTransfersReuseOneNativeContextUntilItsVerifiedClosure() {
		var chest = new Chest();
		chest.delayConfirmation = false;
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, () -> 0L), () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host", observation.epoch());
		var binding = new JsonObject();
		binding.addProperty("windowId", "window-a");
		binding.addProperty("syncId", 7);
		var context = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "retain_container", binding);
		assertEquals(NativeActionRuntime.State.ACCEPTED, runtime.submit(context).state());
		for (int i = 0; i < 10 && !runtime.inspect(context.id()).phase().equals("context_ready"); i++) runtime.tick();
		assertEquals("context_ready", runtime.inspect(context.id()).phase());
		String contextId = (String) runtime.inspect(context.id()).effects().get("contextId");
		int sequence = 1;
		for (int quantity : new int[] {2, 3}) {
			var args = transfer(quantity);
			args.addProperty("contextId", contextId);
			var request = NativeActionRuntime.Request.create(lease, ++sequence, runtime.observe().captureId(), "transfer_container", args);
			assertEquals(NativeActionRuntime.State.ACCEPTED, runtime.submit(request).state());
			for (int i = 0; i < 30 && !runtime.inspect(request.id()).released(); i++) runtime.tick();
			var completed = runtime.inspect(request.id());
			assertEquals(NativeActionRuntime.State.SUCCEEDED, completed.state());
			assertTrue(completed.released());
			assertEquals(quantity, completed.effects().get("transferred"));
			assertEquals(contextId, completed.effects().get("contextId"));
			assertFalse(runtime.inspect(context.id()).released());
			assertTrue(chest.open);
			assertEquals(0, chest.cursor);
		}
		assertEquals(15, chest.stored);
		assertEquals(5, chest.carried);
		assertEquals(1, chest.retentions);
		assertEquals(0, chest.closures);
		runtime.cancel(lease, context.id());
		for (int i = 0; i < 20 && !runtime.inspect(context.id()).released(); i++) runtime.tick();
		assertTrue(runtime.inspect(context.id()).released());
		assertEquals(NativeActionRuntime.State.CANCELLED, runtime.inspect(context.id()).state());
		assertFalse(chest.open);
		assertEquals(1, chest.closures);
		assertEquals(1, chest.releases);
		runtime.release(lease);
		assertFalse(runtime.blocksOrdinaryActions());
	}

	@Test
	void anActionLeaseDoesNotInventContinuousAvailabilityEvidence() {
		var chest = new Chest();
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, () -> 0L), () -> 0L);
		var observation = runtime.observe();
		assertFalse(observation.facts().getAsJsonObject("availability").get("available").getAsBoolean());
		runtime.acquire("host", observation.epoch());
		assertFalse(runtime.observe().facts().getAsJsonObject("availability").get("available").getAsBoolean());
		assertEquals(20, chest.stored);
		assertNull(runtime.authority().active());
	}

	@Test
	void scopeRegistrationIsPassiveBoundedAndExposedThroughTheNativeObservationFacade() {
		var chest = new Chest();
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, () -> 0L), () -> 0L);
		var driver = new NativeDriverService(runtime, () -> 0L, () -> 0L, () -> 0L);
		var args = com.google.gson.JsonParser.parseString("{\"progressScopes\":[{\"scope\":\"farm\",\"chunks\":[{\"x\":1,\"z\":2}]}]}").getAsJsonObject();
		var result = driver.execute("os_observe", args);
		assertEquals("ok", result.get("status").getAsString());
		assertFalse(result.getAsJsonObject("frame").getAsJsonObject("facts").getAsJsonObject("progress").get("available").getAsBoolean());
		assertNull(runtime.authority().lease());
		assertNull(runtime.authority().active());
		assertEquals(20, chest.stored);
		args.addProperty("progressScopes", true);
		assertEquals("rejected", driver.execute("os_observe", args).get("status").getAsString());
	}

	@Test
	void passiveCarriedInventoryRemainsVisibleWithoutOpeningAContainer() {
		var chest = new Chest();
		chest.open = false;
		chest.carried = 3;
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, () -> 0L), () -> 0L);
		var facts = runtime.observe().facts();
		assertNotNull(facts.getAsJsonObject("inventory"));
		var inventory = facts.getAsJsonObject("inventory");
		assertTrue(inventory.get("available").getAsBoolean());
		assertEquals(36, inventory.getAsJsonArray("slots").size());
		assertEquals(3, inventory.getAsJsonArray("slots").get(0).getAsJsonObject().get("count").getAsInt());
		assertFalse(chest.open);
		assertEquals(20, chest.stored);
		assertNull(runtime.authority().lease());
		assertNull(runtime.authority().active());
	}

	@Test
	void aConfirmedExternalWindowCloseCanReleaseAnInterruptedTransferWithoutInventingEffects() {
		var chest = new Chest();
		chest.delayConfirmation = false;
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, () -> 0L), () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		var request = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", transfer(6));
		runtime.submit(request);
		runtime.tick();
		runtime.tick();
		assertEquals(20, chest.cursor);
		runtime.interrupt("world_left");
		// Minecraft closes the handler independently and returns its cursor stack.
		chest.stored += chest.cursor;
		chest.cursor = 0;
		chest.open = false;
		for (int i = 0; i < 20 && !runtime.inspect(request.id()).released(); i++) runtime.tick();
		var result = runtime.inspect(request.id());
		assertTrue(result.released());
		assertEquals(NativeActionRuntime.State.CANCELLED, result.state());
		assertEquals("world_left", result.reason());
		assertEquals(0, result.effects().get("transferred"));
		assertEquals(false, result.effects().get("accountingComplete"));
	}
	@Test
	void anUnrelatedPickupDoesNotPreventReturningTheOwnedCursorDuringCancellation() {
		var chest = new Chest();
		chest.delayConfirmation = false;
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, () -> 0L), () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		var request = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", transfer(6));
		runtime.submit(request);
		runtime.tick();
		runtime.tick();
		assertEquals(20, chest.cursor);
		chest.unrelatedItems = 3;
		runtime.cancel(lease, request.id());
		for (int i = 0; i < 20 && !runtime.inspect(request.id()).released(); i++) runtime.tick();
		assertTrue(runtime.inspect(request.id()).released());
		assertEquals(NativeActionRuntime.State.CANCELLED, runtime.inspect(request.id()).state());
		assertEquals(0, chest.cursor);
		assertEquals(20, chest.stored);
		assertEquals(3, chest.unrelatedItems);
		assertEquals(0, runtime.inspect(request.id()).effects().get("transferred"));
	}
	@Test
	void bundleClickBehaviorIsRejectedBeforeAdmission() {
		var chest = new Chest();
		chest.itemId = "minecraft:bundle";
		chest.stored = 1;
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, () -> 0L), () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		var args = transfer(1);
		args.addProperty("itemId", "minecraft:bundle");
		var request = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", args);
		assertEquals("unsupported_transfer_item", runtime.submit(request).reason());
		assertNull(runtime.authority().active());
		assertEquals(observation.facts(), runtime.observe().facts());
	}
	@Test
	void reflexCleanupStillPermitsPolicyAndCancellationWithoutGrantingAnotherActor() {
		var chest = new Chest();
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, () -> 0L), () -> 0L);
		var driver = new NativeDriverService(runtime, () -> 0L, () -> 0L, () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		runtime.submit(NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", transfer(1)));
		chest.reflex = true;
		runtime.tick();
		assertNull(runtime.authority().lease());
		assertNotNull(runtime.authority().active());
		assertTrue(driver.allowsOrdinaryTool("configure_reflex"));
		assertTrue(driver.allowsOrdinaryTool("cancel_task"));
		assertTrue(driver.allowsOrdinaryTool("inspect_work"));
		assertFalse(driver.allowsOrdinaryTool("navigate_to"));
		assertFalse(driver.allowsOrdinaryTool("take_a_look"));
	}
	@Test
	void nestedEnvelopesAreRejectedBeforeSerializationOrAuthorityChanges() {
		var runtime = new NativeActionRuntime(new NativeContainerActions(new Chest(), () -> 0L), () -> 0L);
		var driver = new NativeDriverService(runtime, () -> 0L, () -> 0L, () -> 0L);
		var args = new JsonObject();
		args.addProperty("action", "acquire");
		args.addProperty("hostId", "host-a");
		args.addProperty("epoch", runtime.observe().epoch());
		var nested = new JsonObject();
		args.add("lease", nested);
		for (int i = 0; i < 32; i++) {
			var child = new JsonObject();
			nested.add("child", child);
			nested = child;
		}
		var result = assertDoesNotThrow(() -> driver.execute("os_lease", args));
		assertEquals("rejected", result.get("status").getAsString());
		assertEquals("invalid_request", result.get("code").getAsString());
		assertNull(runtime.authority().lease());
	}
	@Test
	void aGuardedTransferWaitsForServerConfirmationAndReleasesTheOwnedWindow() {
		var chest = new Chest();
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, () -> 0L), () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		var request = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", transfer(6));
		var admitted = runtime.submit(request);
		assertEquals(admitted, runtime.submit(request));
		runtime.tick();
		runtime.tick();
		assertEquals(0, runtime.observe().facts().getAsJsonObject("window").getAsJsonArray("slots").get(1).getAsJsonObject().get("count").getAsInt());
		assertFalse(runtime.inspect(request.id()).released());
		chest.confirmation.complete(chest.capture());
		chest.delayConfirmation = false;
		for (int i = 0; i < 100 && !runtime.inspect(request.id()).released(); i++) runtime.tick();
		var result = runtime.inspect(request.id());
		assertEquals(NativeActionRuntime.State.SUCCEEDED, result.state());
		assertTrue(result.released());
		assertEquals(6, result.effects().get("transferred"));
		assertFalse(runtime.observe().facts().getAsJsonObject("window").get("open").getAsBoolean());
		assertEquals(result, runtime.submit(request));
	}

	@Test
	void cancellationPreservesTransferredItemsAndReturnsOnlyTheCursorRemainder() {
		var chest = new Chest();
		chest.delayConfirmation = false;
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, () -> 0L), () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		var request = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", transfer(6));
		runtime.submit(request);
		for (int i = 0; i < 10 && runtime.observe().facts().getAsJsonObject("window").getAsJsonArray("slots").get(1).getAsJsonObject().get("count").getAsInt() < 2; i++) runtime.tick();
		runtime.cancel(lease, request.id());
		for (int i = 0; i < 30 && !runtime.inspect(request.id()).released(); i++) runtime.tick();
		var result = runtime.inspect(request.id());
		assertEquals(NativeActionRuntime.State.CANCELLED, result.state());
		assertTrue(result.released());
		assertEquals(2, result.effects().get("transferred"));
		assertEquals(4, result.effects().get("remaining"));
		var window = runtime.observe().facts().getAsJsonObject("window");
		assertEquals(18, window.getAsJsonArray("slots").get(0).getAsJsonObject().get("count").getAsInt());
		assertEquals(0, window.getAsJsonObject("cursor").get("count").getAsInt());
		assertFalse(window.get("open").getAsBoolean());
	}

	@Test
	void nativeAdmissionEnforcesBothItemAndCapacityAllowancesBeforeAnyClick() {
		var chest = new Chest();
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, () -> 0L), () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		var args = transfer(6);
		args.getAsJsonObject("allowance").addProperty("sourceItems", 2);
		var request = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", args);
		assertEquals("allowance_exceeded", runtime.submit(request).reason());
		args = transfer(6);
		args.getAsJsonObject("allowance").addProperty("destinationItems", 2);
		var capacityRequest = NativeActionRuntime.Request.create(lease, 2, observation.captureId(), "transfer_container", args);
		assertEquals("allowance_exceeded", runtime.submit(capacityRequest).reason());
		var oversized = NativeActionRuntime.Request.create(lease, 3, observation.captureId(), "transfer_container", transfer(65));
		assertEquals("invalid_transfer_request", runtime.submit(oversized).reason());
		assertEquals(observation.facts(), runtime.observe().facts());
	}

	@Test
	void releaseWaitsForBothTheServerToCloseAndAllControlsToBeFree() {
		var chest = new Chest();
		chest.delayConfirmation = false;
		chest.closeAcknowledged = false;
		chest.controlsFree = false;
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, () -> 0L), () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		var request = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", transfer(1));
		runtime.submit(request);
		for (int i = 0; i < 15; i++) runtime.tick();
		assertFalse(runtime.inspect(request.id()).released());
		chest.closeAcknowledged = true;
		for (int i = 0; i < 3; i++) runtime.tick();
		assertFalse(runtime.inspect(request.id()).released());
		chest.controlsFree = true;
		for (int i = 0; i < 3; i++) runtime.tick();
		assertTrue(runtime.inspect(request.id()).released());
	}

	@Test
	void theDriverProtocolRejectsUnknownVersionsAndCanQueryALostSubmissionResponse() {
		var chest = new Chest();
		chest.delayConfirmation = false;
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, () -> 0L), () -> 0L);
		var driver = new NativeDriverService(runtime, () -> 0L, () -> 0L, () -> 0L);
		var frame = driver.execute("os_observe", new JsonObject()).getAsJsonObject("frame");
		assertEquals(1, frame.get("schemaVersion").getAsInt());
		assertEquals(1, frame.get("captureSequence").getAsLong());
		assertFalse(frame.get("sessionId").getAsString().isBlank());
		assertEquals("known", frame.getAsJsonObject("facts").getAsJsonObject("coverage").get("state").getAsString());
		var acquire = new JsonObject();
		acquire.addProperty("action", "acquire");
		acquire.addProperty("hostId", "host-a");
		acquire.addProperty("epoch", frame.get("epoch").getAsString());
		var json = new com.google.gson.Gson();
		var lease = json.fromJson(driver.execute("os_lease", acquire).get("lease"), NativeActionRuntime.Lease.class);
		var request = NativeActionRuntime.Request.create(lease, 1, frame.get("captureId").getAsString(), "transfer_container", transfer(1));
		var submission = json.toJsonTree(request).getAsJsonObject();
		submission.addProperty("schemaVersion", 2);
		assertEquals("unsupported_schema", driver.execute("os_submit", submission).get("code").getAsString());
		submission.addProperty("schemaVersion", 1);
		driver.execute("os_submit", submission); // Response deliberately discarded.
		var inspect = new JsonObject();
		inspect.add("id", json.toJsonTree(request.id()));
		assertEquals("ACCEPTED", driver.execute("os_inspect", inspect).getAsJsonObject("receipt").get("state").getAsString());
		for (int i = 0; i < 20; i++) driver.tick();
		assertEquals(1, driver.execute("os_inspect", inspect).getAsJsonObject("receipt").getAsJsonObject("effects").get("transferred").getAsInt());
		assertEquals("SUCCEEDED", driver.execute("os_submit", submission).getAsJsonObject("receipt").get("state").getAsString());
		var outcome = driver.execute("os_inspect", inspect).getAsJsonObject("receipt");
		assertEquals(frame.get("captureId"), outcome.getAsJsonObject("basis").get("captureId"));
		assertEquals(submission.get("payloadHash"), outcome.getAsJsonObject("basis").get("payloadHash"));
		assertTrue(outcome.getAsJsonObject("effects").getAsJsonObject("releaseEvidence").get("serverWindowClosed").getAsBoolean());
		var history = new JsonObject();
		history.addProperty("sessionId", frame.get("sessionId").getAsString());
		history.addProperty("sinceSeqNo", 0);
		history.addProperty("limit", 32);
		var events = driver.execute("os_inspect", history).getAsJsonObject("history");
		assertFalse(events.get("gap").getAsBoolean());
		var tail = events.getAsJsonArray("events").asList().getLast().getAsJsonObject();
		assertEquals(outcome, tail.getAsJsonObject("receipt"));
	}

	@Test
	void aQueuedClickRejectedByTheServerCannotBeReportedAsATransfer() {
		var chest = new Chest();
		chest.delayConfirmation = false;
		chest.rejectClick = true;
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, () -> 0L), () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		var request = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", transfer(6));
		runtime.submit(request);
		for (int i = 0; i < 3; i++) runtime.tick();
		assertEquals("container_changed", runtime.inspect(request.id()).reason());
		assertNotEquals(NativeActionRuntime.State.SUCCEEDED, runtime.inspect(request.id()).state());
		assertEquals(0, runtime.observe().facts().getAsJsonObject("window").getAsJsonArray("slots").get(1).getAsJsonObject().get("count").getAsInt());
	}

	@Test
	void cancellationAlsoFencesAnAlreadyQueuedWindowClose() {
		var chest = new Chest();
		chest.delayConfirmation = false;
		chest.deferClose = true;
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, () -> 0L), () -> 0L);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		var request = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", transfer(1));
		runtime.submit(request);
		for (int i = 0; i < 20 && chest.queuedClose == null; i++) runtime.tick();
		runtime.cancel(lease, request.id());
		chest.queuedClose.run();
		assertTrue(runtime.observe().facts().getAsJsonObject("window").get("open").getAsBoolean());
		chest.deferClose = false;
		for (int i = 0; i < 20 && !runtime.inspect(request.id()).released(); i++) runtime.tick();
		assertTrue(runtime.inspect(request.id()).released());
		assertEquals(NativeActionRuntime.State.CANCELLED, runtime.inspect(request.id()).state());
		assertEquals(1, runtime.inspect(request.id()).effects().get("transferred"));
	}

	@Test
	void malformedTransferArgumentsAreRejectedBeforeAnyNativeAdmission() {
		var fractional = transfer(1);
		fractional.addProperty("quantity", 1.5);
		var missingAllowance = transfer(1);
		missingAllowance.remove("allowance");
		var unknownField = transfer(1);
		unknownField.addProperty("ignoreAllowance", true);
		for (var args : List.of(fractional, missingAllowance, unknownField)) {
			var chest = new Chest();
			var runtime = new NativeActionRuntime(new NativeContainerActions(chest, () -> 0L), () -> 0L);
			var observation = runtime.observe();
			var lease = runtime.acquire("host-a", observation.epoch());
			var request = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", args);
			assertEquals("invalid_transfer_request", runtime.submit(request).reason());
			assertNull(runtime.authority().active());
			assertEquals(observation.facts(), runtime.observe().facts());
		}
	}

	@Test
	void aConfirmationTimeoutStopsWorkWithoutPretendingThatCleanupHasFinished() {
		var clock = new java.util.concurrent.atomic.AtomicLong();
		var chest = new Chest();
		var runtime = new NativeActionRuntime(new NativeContainerActions(chest, clock::get), clock::get);
		var observation = runtime.observe();
		var lease = runtime.acquire("host-a", observation.epoch());
		var request = NativeActionRuntime.Request.create(lease, 1, observation.captureId(), "transfer_container", transfer(6));
		runtime.submit(request);
		runtime.tick();
		for (int seconds = 1; seconds <= 30; seconds++) {
			clock.set(seconds * 1_000_000_000L);
			runtime.heartbeat(lease);
			runtime.tick();
		}
		assertEquals("confirmation_timeout", runtime.inspect(request.id()).reason());
		assertFalse(runtime.inspect(request.id()).released());
		assertFalse((boolean) runtime.inspect(request.id()).effects().get("serverConfirmed"));
		chest.confirmation.complete(chest.capture());
		chest.delayConfirmation = false;
		for (int i = 0; i < 20 && !runtime.inspect(request.id()).released(); i++) runtime.tick();
		assertTrue(runtime.inspect(request.id()).released());
		assertEquals(NativeActionRuntime.State.FAILED, runtime.inspect(request.id()).state());
		assertEquals(0, runtime.inspect(request.id()).effects().get("transferred"));
	}

	private static JsonObject transfer(int quantity) {
		var args = new JsonObject();
		args.addProperty("windowId", "window-a");
		args.addProperty("syncId", 7);
		args.addProperty("direction", "withdraw");
		args.addProperty("itemId", "minecraft:wheat");
		args.addProperty("quantity", quantity);
		var allowance = new JsonObject();
		allowance.addProperty("sourceItems", quantity);
		allowance.addProperty("destinationItems", quantity);
		args.add("allowance", allowance);
		return args;
	}

	/** Fake Minecraft inventory and delayed server response, outside the tested native seam. */
	private static final class Chest implements NativeContainerActions.Access {
		public List<NativeContainerActions.Slot> inventory() {
			return java.util.stream.IntStream.range(0, 36).mapToObj(index -> new NativeContainerActions.Slot(index, false,
				index == 0 && carried > 0 ? itemId : "", "", index == 0 ? carried : 0, 64)).toList();
		}
		private String itemId = "minecraft:wheat";
		private int stored = 20;
		private int carried;
		private int cursor;
		private int unrelatedItems;
		private int retentions;
		private int releases;
		private int closures;
		private boolean open = true;
		private boolean delayConfirmation = true;
		private boolean closeAcknowledged = true;
		private boolean controlsFree = true;
		private boolean reflex;
		private boolean rejectClick;
		private boolean deferClose;
		private boolean failClose;
		private Runnable queuedClose;
		private CompletableFuture<NativeContainerActions.Window> confirmation;
		@Override public void retainWindow(String id) { retentions++; }
		@Override public void releaseWindow(String id) { releases++; }
		@Override public NativeActionRuntime.World world() {
			return new NativeActionRuntime.World("save", "minecraft:overworld", "load", true, false, reflex);
		}
		@Override public NativeContainerActions.Window capture() {
			return new NativeContainerActions.Window(open, open ? "window-a" : "", open ? 7 : 0,
				List.of(slot(0, true, stored), slot(27, false, carried), slot(28, false, unrelatedItems)), slot(-1, false, cursor));
		}
		private NativeContainerActions.Slot slot(int id, boolean container, int count) {
			return new NativeContainerActions.Slot(id, container, count == 0 ? "" : itemId, count == 0 ? "" : "plain", count, 64);
		}
		@Override public CompletableFuture<NativeContainerActions.Window> confirm(String windowId) {
			var captured = capture();
			if (!open && !closeAcknowledged) captured = new NativeContainerActions.Window(true, "window-a", 7, captured.slots(), captured.cursor());
			confirmation = delayConfirmation ? new CompletableFuture<>() : CompletableFuture.completedFuture(captured);
			return confirmation;
		}
		@Override public CompletableFuture<NativeContainerActions.Window> click(NativeContainerActions.Window expected, int slot, int button,
			NativeActionRuntime.EffectPermit permit, NativeActionRuntime.Permission permission) {
			if (rejectClick) return CompletableFuture.failedFuture(new NativeActionRuntime.Rejected("container_changed"));
			assertTrue(open);
			assertEquals("window-a", expected.windowId());
			assertEquals(7, expected.syncId());
			assertTrue(permit.allows(permission));
			if (slot == 0) { int previous = stored; stored = cursor; cursor = previous; }
			else if (button == 1 && cursor > 0) { carried++; cursor--; }
			else throw new AssertionError("unexpected click");
			return CompletableFuture.completedFuture(capture());
		}
		@Override public CompletableFuture<NativeContainerActions.Window> close(NativeContainerActions.Window expected,
			NativeActionRuntime.EffectPermit permit, NativeActionRuntime.Permission permission) {
			if (failClose) throw new IllegalStateException("injected context close failure");
			var future = new CompletableFuture<NativeContainerActions.Window>();
			queuedClose = () -> {
				try {
					permit.perform(permission, () -> { assertEquals(0, cursor); open = false; closures++; });
					future.complete(confirm(expected.windowId()).join());
				} catch (RuntimeException exception) { future.completeExceptionally(exception); }
			};
			if (!deferClose) queuedClose.run();
			return future;
		}
		@Override public boolean controlsReleased() { return controlsFree; }
	}
}
