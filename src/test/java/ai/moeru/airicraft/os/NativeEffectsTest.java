package ai.moeru.airicraft.os;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class NativeEffectsTest {
	@TempDir Path directory;
	@Test void aTimedOutClientRequestIsCancelledBeforeItCanRunLate() throws Exception {
		var queued = new CompletableFuture<com.google.gson.JsonObject>();
		try (var effects = new NativeEffects((name, args) -> queued, new EffectJournal(directory), "save", () -> System.nanoTime() / 1_000_000)) {
			assertThrows(java.util.concurrent.TimeoutException.class, effects::observe);
			assertTrue(queued.isCancelled());
		}
	}
	@Test void contextPollingAndChildPollingCountOneOperationOnce() throws Exception {
		try (var minecraft = new OsMinecraftFixture(); var effects = new NativeEffects(minecraft::call, new EffectJournal(directory), "save", () -> System.nanoTime() / 1_000_000)) {
			effects.start();
			effects.retain("container:home", frame -> OsJson.obj("windowId", "window-a", "syncId", 7));
			long deadline = System.nanoTime() + 5_000_000_000L;
			while (!effects.context().phase().equals("ready") && System.nanoTime() < deadline) { Thread.sleep(10); effects.pollContext(false); }
			var receipt = effects.execute("transfer_container", frame -> OsJson.obj("windowId", "window-a", "syncId", 7, "direction", "withdraw", "itemId", "minecraft:wheat", "quantity", 1, "allowance", OsJson.obj("sourceItems", 1, "destinationItems", 1)), "definition", "invocation");
			while (System.nanoTime() < deadline) {
				var seen = minecraft.call("os_inspect", OsJson.obj("id", receipt.get("id"))).get().getAsJsonObject("receipt");
				if (NativeEffects.released(seen)) break;
				Thread.sleep(10);
			}
			effects.pollContext(false); assertTrue(NativeEffects.released(effects.poll()));
			assertEquals(1, effects.context().operations());
			minecraft.client.submit(() -> minecraft.ticks += 1300).get();
			assertEquals("context_budget", assertThrows(IllegalStateException.class, () -> effects.execute("transfer_container", frame -> { fail("expired context must not prepare an action"); return null; }, "definition", "invocation")).getMessage());
		}
	}
	@Test void droppedAdmissionReplyIsInspectedWithoutRepeatingTheTransfer() throws Exception {
		try (var minecraft = new OsMinecraftFixture()) {
			var dropped = new AtomicBoolean();
			NativeAccess port = (name, args) -> minecraft.call(name, args).thenCompose(result -> name.equals("os_submit") && dropped.compareAndSet(false, true)
				? CompletableFuture.failedFuture(new IllegalStateException("lost_reply")) : CompletableFuture.completedFuture(result));
			try (var effects = new NativeEffects(port, new EffectJournal(directory), "save", () -> System.nanoTime() / 1_000_000)) {
				effects.start();
				var receipt = effects.execute("transfer_container", frame -> OsJson.obj("windowId", "window-a", "syncId", 7,
					"direction", "withdraw", "itemId", "minecraft:wheat", "quantity", 2, "allowance", OsJson.obj("sourceItems", 2, "destinationItems", 2)), "definition", "invocation");
				long deadline = System.nanoTime() + 5_000_000_000L;
				while (!NativeEffects.released(receipt) && System.nanoTime() < deadline) { Thread.sleep(10); receipt = effects.poll(); }
				assertTrue(NativeEffects.released(receipt)); assertEquals(2, minecraft.carried());
				assertTrue(new EffectJournal(directory).unfinished().isEmpty());
			}
		}
	}
	@Test void unfinishedIntentSurvivesReopeningAndConflictingIdentityIsRejected() throws Exception {
		var journal = new EffectJournal(directory);
		var intent = OsJson.obj("id", OsJson.obj("epoch", "e", "generation", 1, "sequence", 1), "request", OsJson.obj("operation", "transfer"), "payloadHash", "hash", "definition", "a", "invocation", "b");
		journal.record(intent);
		assertEquals(1, new EffectJournal(directory).unfinished().size());
		var conflicting = intent.deepCopy(); conflicting.addProperty("payloadHash", "different");
		assertThrows(IllegalStateException.class, () -> journal.record(conflicting));
	}
}
