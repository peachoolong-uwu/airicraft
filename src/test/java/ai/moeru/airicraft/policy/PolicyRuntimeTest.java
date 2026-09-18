package ai.moeru.airicraft.policy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class PolicyRuntimeTest {
	private static final class Host implements PolicyRuntime.Host {
		final List<JsonObject> calls = new ArrayList<>();
		CompletableFuture<JsonElement> effect;
		boolean closed;
		@Override public CompletableFuture<JsonElement> execute(JsonObject effect) {
			calls.add(effect.deepCopy());
			return this.effect = new CompletableFuture<>();
		}
		@Override public void close() { closed = true; }
		void complete(String json) { effect.complete(JsonParser.parseString(json)); }
	}

	@Test void restockingSuspendsUntilVerifiedTransferThenContinuesWithoutInference() throws Exception {
		String source;
		try (var stream = getClass().getResourceAsStream("/airicraft/policies/restock-open-container.js")) {
			source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		var host = new Host();
		var outcome = new AtomicReference<PolicyRuntime.Outcome>();
		try (var runtime = new PolicyRuntime(source, JsonParser.parseString("{\"stock\":{\"minecraft:bread\":8}}"), host, outcome::set)) {
			pump(runtime, () -> host.calls.size() == 1);
			host.complete("{\"syncId\":7,\"container\":{\"minecraft:bread\":20},\"inventory\":{\"minecraft:bread\":3}}");
			pump(runtime, () -> host.calls.size() == 2);
			assertEquals("withdraw", host.calls.get(1).get("operation").getAsString());
			assertEquals(5, host.calls.get(1).getAsJsonObject("arguments").getAsJsonArray("items").get(0).getAsJsonObject().get("quantity").getAsInt());
			for (int i = 0; i < 20; i++) runtime.tick();
			assertNull(outcome.get());
			assertEquals(2, host.calls.size(), "An unresolved transfer must not be retried or followed by close");
			host.complete("{\"syncId\":7,\"container\":{\"minecraft:bread\":15},\"inventory\":{\"minecraft:bread\":8}}");
			pump(runtime, () -> host.calls.size() == 3);
			assertEquals("close_container", host.calls.get(2).get("operation").getAsString());
			host.complete("{\"closed\":true}");
			pump(runtime, () -> outcome.get() != null);
			assertEquals("SUCCEEDED", outcome.get().state());
			assertEquals(8, outcome.get().result().getAsJsonObject().getAsJsonObject("inventory").get("minecraft:bread").getAsInt());
			assertEquals(3, outcome.get().effects().size());
			assertTrue(host.closed);
		}
	}

	@Test void cancellationNeverResumesAfterLateNativeCompletion() throws Exception {
		var host = new Host();
		var outcome = new AtomicReference<PolicyRuntime.Outcome>();
		try (var runtime = new PolicyRuntime("function* main(p) { yield p.observeContainer(); yield p.closeContainer(7); }", new JsonObject(), host, outcome::set)) {
			pump(runtime, () -> host.calls.size() == 1);
			runtime.cancel("safety_interruption");
			host.complete("{\"syncId\":7}");
			for (int i = 0; i < 10; i++) runtime.tick();
			assertEquals(1, host.calls.size());
			assertEquals("CANCELLED", outcome.get().state());
		}
	}

	@Test void failedTransferStopsAndPreservesEarlierEvidence() throws Exception {
		var host = new Host();
		var outcome = new AtomicReference<PolicyRuntime.Outcome>();
		try (var runtime = new PolicyRuntime("function* main(p) { yield p.observeContainer(); yield p.withdraw(7, []); yield p.closeContainer(7); }", new JsonObject(), host, outcome::set)) {
			pump(runtime, () -> host.calls.size() == 1);
			host.complete("{\"syncId\":7}");
			pump(runtime, () -> host.calls.size() == 2);
			host.effect.completeExceptionally(new IllegalStateException("container_changed"));
			pump(runtime, () -> outcome.get() != null);
			assertEquals("FAILED", outcome.get().state());
			assertEquals("container_changed", outcome.get().reason());
			assertTrue(outcome.get().effects().getFirst().has("result"));
			assertEquals(2, host.calls.size());
		}
	}

	@Test void rejectsHostAccessAndStopsInfiniteGuestLoop() throws Exception {
		for (String source : List.of("function* main() { Java.type('java.lang.System'); }", "function* main() { while(true) {} }")) {
			var outcome = new AtomicReference<PolicyRuntime.Outcome>();
			var host = new Host();
			try (var runtime = new PolicyRuntime(source, new JsonObject(), host, outcome::set)) {
				pump(runtime, () -> outcome.get() != null);
				assertEquals("FAILED", outcome.get().state());
				assertTrue(host.calls.isEmpty());
			}
		}
	}

	@Test void boundsYieldingLoopsToo() throws Exception {
		var host = new Host();
		var outcome = new AtomicReference<PolicyRuntime.Outcome>();
		try (var runtime = new PolicyRuntime("function* main(p) { while(true) yield p.observeContainer(); }", new JsonObject(), host, outcome::set)) {
			pump(runtime, () -> {
				if (host.effect != null && !host.effect.isDone()) host.complete("{}");
				return outcome.get() != null;
			});
			assertEquals("policy_effect_limit", outcome.get().reason());
			assertEquals(32, host.calls.size());
		}
	}

	private static void pump(PolicyRuntime runtime, BooleanSupplier finished) throws InterruptedException {
		long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
		while (!finished.getAsBoolean() && System.nanoTime() < deadline) {
			runtime.tick();
			Thread.sleep(15);
		}
		assertTrue(finished.getAsBoolean(), "Policy did not reach expected state");
	}
}
