package ai.moeru.airicraft.os;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
class EmbeddedOsRuntimeTest {
	@TempDir Path directory;
	@Test void aGeneratorCanRecoverFromAnUnsupportedOperation() throws Exception {
		try (var minecraft = new OsMinecraftFixture(); var runtime = new EmbeddedOsRuntime(directory, minecraft::call)) {
			runtime.start(new JsonObject()).get(15, TimeUnit.SECONDS);
			var definition = SkillLibraryTest.definition("recover", "function* main(os) { const outcome = yield os.work('missing', {}); return outcome.reason; }");
			definition.add("outputContract", OsJson.obj("type", "string"));
			definition.add("examples", OsJson.json(java.util.List.of(OsJson.obj("input", null, "responses", java.util.List.of(OsJson.obj("status", "rejected", "reason", "operation_unknown")), "expected", java.util.List.of(
				OsJson.obj("done", false, "value", OsJson.obj("kind", "work", "operation", "missing", "arguments", new JsonObject(), "context", null)), OsJson.obj("done", true, "value", "operation_unknown"))))));
			String id = install(runtime, definition); awaitFinished(runtime, id);
			assertEquals("operation_unknown", runtime.inspect(id).get().getAsJsonObject("outcome").get("value").getAsString());
		}
	}
	@Test void failedInitializationReleasesTheDirectoryForRepairAndRetry() throws Exception {
		java.nio.file.Files.writeString(directory.resolve("effects.json"), "invalid");
		try (var minecraft = new OsMinecraftFixture(); var runtime = new EmbeddedOsRuntime(directory, minecraft::call)) {
			assertThrows(java.util.concurrent.ExecutionException.class, () -> runtime.propose(SkillLibraryTest.definition("answer", "function* main() { return 42; }"), "check").get());
			java.nio.file.Files.delete(directory.resolve("effects.json"));
			assertNotNull(runtime.propose(SkillLibraryTest.definition("answer", "function* main() { return 42; }"), "after repair").get());
		}
	}
	@Test void twoIndependentSkillsReuseOneNativeContainerVisit() throws Exception {
		try (var minecraft = new OsMinecraftFixture(); var runtime = new EmbeddedOsRuntime(directory, minecraft::call)) {
			runtime.start(OsJson.parse("{\"bindOpenContainer\":true}").getAsJsonObject()).get(15, TimeUnit.SECONDS);
			String first = stage(runtime, transfer("one", 1)), second = stage(runtime, transfer("two", 2));
			var batch = new JsonArray(); batch.add(OsJson.obj("digest", first, "input", null)); batch.add(OsJson.obj("digest", second, "input", null));
			var installed = runtime.installAll(batch).get().getAsJsonArray("installations");
			String one = installed.get(0).getAsJsonObject().get("installationId").getAsString(), two = installed.get(1).getAsJsonObject().get("installationId").getAsString();
			JsonObject state = awaitFinished(runtime, one, two);
			assertEquals(3, minecraft.carried());
			assertEquals(1, minecraft.client.submit(() -> minecraft.chest.visits).get());
			assertEquals(1, minecraft.client.submit(() -> minecraft.chest.closures).get());
			assertFalse(state.getAsJsonObject("native").get("unresolved").getAsBoolean());
		}
	}
	@Test void independentDemandConsumersShareOneProcurementBatch() throws Exception {
		try (var minecraft = new OsMinecraftFixture(); var runtime = new EmbeddedOsRuntime(directory, minecraft::call)) {
			String wheat = ContainerOperation.resource("player", "minecraft:wheat", "plain");
			var config = OsJson.obj("bindOpenContainer", true,
				"resources", OsJson.obj("wheat", OsJson.obj("key", wheat, "grant", "resource:wheat", "methods", java.util.List.of("chest"))),
				"supplies", OsJson.obj("withdraw", OsJson.obj("resource", "wheat", "operation", "chest", "arguments", OsJson.obj("direction", "withdraw", "itemId", "minecraft:wheat"), "maximum", 64)));
			runtime.start(config).get(15, TimeUnit.SECONDS);
			var definition = SkillLibraryTest.definition("demand", "function* main(os,input) { const delivered = yield os.demand('wheat',input,['chest']); return delivered.credited; }");
			definition.add("capabilities", OsJson.json(java.util.List.of("container:home", "resource:wheat")));
			var example = OsJson.obj("input", 2, "responses", java.util.List.of(OsJson.obj("credited", 2)), "expected", java.util.List.of(
				OsJson.obj("done", false, "value", OsJson.obj("kind", "demand", "resource", "wheat", "quantity", 2, "methods", java.util.List.of("chest"))), OsJson.obj("done", true, "value", 2)));
			definition.add("examples", OsJson.json(java.util.List.of(example)));
			String digest = stage(runtime, definition); var inputs = new JsonArray(); inputs.add(OsJson.obj("digest", digest, "input", 2)); inputs.add(OsJson.obj("digest", digest, "input", 3));
			var installed = runtime.installAll(inputs).get().getAsJsonArray("installations");
			String a = installed.get(0).getAsJsonObject().get("installationId").getAsString(), b = installed.get(1).getAsJsonObject().get("installationId").getAsString();
			awaitFinished(runtime, a, b);
			assertEquals(5, minecraft.carried()); assertEquals(1, minecraft.client.submit(() -> minecraft.chest.pickups).get());
			assertEquals(2, runtime.inspect(a).get().getAsJsonObject("outcome").get("value").getAsInt());
			assertEquals(3, runtime.inspect(b).get().getAsJsonObject("outcome").get("value").getAsInt());
		}
	}
	@Test void waitingSkillDoesNotPreventAnotherSkillFromCompleting() throws Exception {
		try (var minecraft = new OsMinecraftFixture(); var runtime = new EmbeddedOsRuntime(directory, minecraft::call)) {
			runtime.start(new JsonObject()).get(15, TimeUnit.SECONDS);
			var waiting = SkillLibraryTest.definition("waiting", "function* main(os) { yield os.wait({scope:'inventory',path:['never'],equals:true}); return 42; }");
			waiting.add("capabilities", OsJson.json(java.util.List.of("observe:inventory")));
			waiting.getAsJsonArray("examples").get(0).getAsJsonObject().getAsJsonArray("expected").set(0, OsJson.obj("done", false, "value", OsJson.obj("kind", "wait", "condition", OsJson.obj("scope", "inventory", "path", java.util.List.of("never"), "equals", true), "options", new JsonObject())));
			String waitId = install(runtime, waiting);
			String doneId = install(runtime, SkillLibraryTest.definition("answer", "function* main() { return 42; }"));
			awaitFinished(runtime, doneId);
			assertEquals("installed", runtime.inspect(waitId).get().get("phase").getAsString());
			runtime.stop(waitId).get();
			awaitFinished(runtime, waitId);
		}
	}
	static String install(EmbeddedOsRuntime runtime, JsonObject definition) throws Exception {
		String digest = stage(runtime, definition);
		return runtime.install(digest, null).get(15, TimeUnit.SECONDS).get("installationId").getAsString();
	}
	static String stage(EmbeddedOsRuntime runtime, JsonObject definition) throws Exception {
		String digest = runtime.propose(definition, "Java migration integration check").get(15, TimeUnit.SECONDS);
		var validation = runtime.validate(digest).get(15, TimeUnit.SECONDS);
		assertTrue(validation.get("passed").getAsBoolean(), validation.toString());
		return digest;
	}
	static JsonObject awaitFinished(EmbeddedOsRuntime runtime, String... ids) throws Exception {
		long deadline = System.nanoTime() + 15_000_000_000L;
		while (System.nanoTime() < deadline) {
			boolean finished = true;
			for (String id : ids) finished &= runtime.inspect(id).get().get("phase").getAsString().equals("retired");
			var status = runtime.status().get();
			if (status.has("fault") && !status.get("fault").isJsonNull()) fail(status.toString());
			if (finished && !status.getAsJsonObject("native").get("unresolved").getAsBoolean()) return status;
			Thread.sleep(20);
		}
		throw new AssertionError(runtime.status().get().toString());
	}
	private static JsonObject transfer(String name, int quantity) {
		var definition = SkillLibraryTest.definition(name, "function* main(os) { const result = yield os.work('chest', {direction:'withdraw',itemId:'minecraft:wheat',quantity:" + quantity + "}, 'container:home'); return 42; }");
		definition.add("capabilities", OsJson.json(java.util.List.of("container:home")));
		var example = definition.getAsJsonArray("examples").get(0).getAsJsonObject();
		var expected = new JsonArray(); expected.add(OsJson.obj("done", false, "value", OsJson.obj("kind", "work", "operation", "chest", "arguments", OsJson.obj("direction", "withdraw", "itemId", "minecraft:wheat", "quantity", quantity), "context", "container:home")));
		expected.add(OsJson.obj("done", true, "value", 42)); example.add("expected", expected); example.add("responses", OsJson.json(java.util.List.of(OsJson.obj("status", "success"))));
		return definition;
	}
}
