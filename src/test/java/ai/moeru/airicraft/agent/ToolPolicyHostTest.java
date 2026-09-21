package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.llm.ExternalPlannerToolResult;
import ai.moeru.airicraft.agent.work.*;
import ai.moeru.airicraft.policy.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class ToolPolicyHostTest {
	@Test void namedFunctionsAwaitWorkAndCanBranchOnNativeFailure() throws Exception {
		var calls = new ArrayList<String>();
		var state = new AtomicReference<>(snapshot(WorkSnapshot.State.RUNNING));
		var cancelled = new AtomicBoolean();
		var host = new ToolPolicyHost((name, args) -> {
			calls.add(name);
			if (name.equals("navigate_to")) return output(name, "{\"accepted\":true,\"workId\":\"JOB:child\",\"state\":\"RUNNING\"}");
			if (name.equals("craft_recipe")) return output(name, "TOOL_ERROR: missing_ingredients");
			return output(name, "{\"counts\":{\"minecraft:stone\":3}}");
		}, ignored -> new JsonObject(), ignored -> Optional.of(state.get()), () -> cancelled.set(true),
			() -> { throw new AssertionError("No chest required"); });
		var outcome = new AtomicReference<PolicyRuntime.Outcome>();
		try (var runtime = new PolicyRuntime("""
			function* main(p) {
			  const moved = yield p.navigateTo({x:1,y:64,z:2,exactY:true});
			  if (!moved.ok || moved.work.state !== 'SUCCEEDED') return {bad: moved};
			  const craft = yield p.craftRecipe({recipeId:'minecraft:furnace',times:1});
			  if (craft.ok) return {bad: craft};
			  return yield p.queryWorld({source:'function query(w) { return {}; }',input:{}});
			}
			""", new JsonObject(), host, outcome::set)) {
			pump(runtime, () -> calls.size() == 1);
			for (int i = 0; i < 20; i++) runtime.tick();
			assertEquals(List.of("navigate_to"), calls, "Admission must not advance the script");
			state.set(snapshot(WorkSnapshot.State.SUCCEEDED));
			pump(runtime, () -> outcome.get() != null);
			assertEquals(List.of("navigate_to", "craft_recipe", "query_world"), calls);
			assertEquals("SUCCEEDED", outcome.get().state());
			assertTrue(outcome.get().result().getAsJsonObject().get("ok").getAsBoolean());
			assertEquals(3, outcome.get().effects().size());
		}
		assertTrue(cancelled.get());
	}

	@Test void failedChildOverridesAcceptedReceiptAndCancellationIgnoresLateCompletion() {
		var state = new AtomicReference<>(snapshot(WorkSnapshot.State.FAILED));
		var future = new CompletableFuture<ExternalPlannerToolResult>();
		var cancelled = new AtomicInteger();
		var host = new ToolPolicyHost((name, args) -> future, ignored -> new JsonObject(),
			ignored -> Optional.of(state.get()), cancelled::incrementAndGet, () -> null);
		var result = host.execute(effect("navigate_to"));
		future.complete(new ExternalPlannerToolResult("navigate_to", "Tool result for navigate_to: {\"accepted\":true,\"workId\":\"JOB:child\"}", null));
		host.tick();
		assertFalse(result.join().getAsJsonObject().get("ok").getAsBoolean());
		assertEquals("FAILED", result.join().getAsJsonObject().getAsJsonObject("work").get("state").getAsString());
		state.set(snapshot(WorkSnapshot.State.RUNNING));
		var second = host.execute(effect("navigate_to"));
		host.tick();
		assertFalse(second.isDone());
		host.close(); host.close();
		state.set(snapshot(WorkSnapshot.State.SUCCEEDED)); host.tick();
		assertTrue(second.isCompletedExceptionally());
		assertEquals(1, cancelled.get());
	}

	@Test void recursiveAndPlannerControlToolsCannotBeForgedAsEffects() {
		var host = new ToolPolicyHost((name, args) -> { throw new AssertionError("must not dispatch"); },
			ignored -> new JsonObject(), ignored -> Optional.empty(), () -> {}, () -> null);
		for (String name : List.of("run_policy", "cancel_work", "finish_planner_goal", "something_wrong", "take_a_look"))
			assertFalse(host.execute(effect(name)).join().getAsJsonObject().get("ok").getAsBoolean());
	}

	@Test void schemaHelperUsesTheSameNativeToolAndPureScriptsNeedNoContainer() throws Exception {
		var host = new ToolPolicyHost((name, args) -> { throw new AssertionError(); },
			name -> new JsonPrimitive(name), ignored -> Optional.empty(), () -> {}, () -> { throw new AssertionError(); });
		var outcome = new AtomicReference<PolicyRuntime.Outcome>();
		try (var runtime = new PolicyRuntime("function* main(p) { return yield p.describe('mineBlocks'); }", new JsonObject(), host, outcome::set)) {
			pump(runtime, () -> outcome.get() != null);
			assertEquals("mine_blocks", outcome.get().result().getAsString());
		}
	}

	private static JsonObject effect(String name) {
		return JsonParser.parseString("{\"operation\":\"call_tool\",\"arguments\":{\"name\":\"" + name + "\",\"args\":{}}}").getAsJsonObject();
	}
	private static WorkSnapshot snapshot(WorkSnapshot.State state) {
		return new WorkSnapshot(new WorkHandle("JOB:child"), "OPERATION:policy", state, "navigation", "test", !state.terminal(), 1, Map.of());
	}
	private static CompletableFuture<ExternalPlannerToolResult> output(String name, String value) {
		return CompletableFuture.completedFuture(new ExternalPlannerToolResult(name, value.startsWith("TOOL_ERROR:") ? value : "Tool result for " + name + ": " + value, null));
	}
	private static void pump(PolicyRuntime runtime, BooleanSupplier done) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (!done.getAsBoolean() && System.nanoTime() < deadline) { runtime.tick(); Thread.sleep(5); }
		assertTrue(done.getAsBoolean());
	}
}
