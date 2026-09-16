package ai.moeru.airicraft.os;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
class InferenceWorkersTest {
	@TempDir Path directory;
	@Test void bundledWorkerRunsItsPinnedFallbackInsideTheModRuntime() throws Exception {
		try (var minecraft = new OsMinecraftFixture(); var runtime = new EmbeddedOsRuntime(directory, minecraft::call)) {
			runtime.start(new com.google.gson.JsonObject()).get(15, TimeUnit.SECONDS);
			var references = runtime.bundled().get(25, TimeUnit.SECONDS);
			assertEquals(8, references.size());
			var input = OsJson.obj("name", "sheep pen", "description", "Two sheep on grass.");
			String id = runtime.install(references.get("describe_structure").getAsString(), input).get().get("installationId").getAsString();
			EmbeddedOsRuntimeTest.awaitFinished(runtime, id);
			var outcome = runtime.inspect(id).get().getAsJsonObject("outcome").getAsJsonObject("value");
			assertEquals("fallback", outcome.get("status").getAsString());
			assertEquals("worker_unconfigured", outcome.get("reason").getAsString());
			assertEquals("Structure description unavailable.", outcome.get("value").getAsString());
		}
	}
	@Test void identicalCallsShareInferenceAndOneCancellationLeavesTheOtherSubscriber() throws Exception {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); var requests = new AtomicInteger(); var release = new CompletableFuture<Void>();
		server.createContext("/complete", exchange -> {
			requests.incrementAndGet();
			var payload = OsJson.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
			assertEquals(512, payload.get("max_completion_tokens").getAsInt()); assertTrue(payload.getAsJsonArray("tools").isEmpty());
			try { release.get(5, TimeUnit.SECONDS); } catch (Exception failure) { throw new java.io.IOException(failure); }
			byte[] body = OsJson.canonical(OsJson.obj("model", "fixture", "choices", List.of(OsJson.obj("finish_reason", "stop", "message", OsJson.obj("role", "assistant", "content", "{\"value\":42}"))), "usage", Map.of("prompt_tokens", 3, "completion_tokens", 2, "total_tokens", 5))).getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
		}); server.start();
		try (var engine = new GraalSkillEngine()) {
			var fallback = new SkillDefinition(SkillLibraryTest.definition("fallback", "function* main() { return 42; }"));
			var value = fallback.value(); value.remove("mode"); value.remove("source"); value.addProperty("kind", "worker"); value.addProperty("name", "answer_worker");
			value.addProperty("prompt", "Return the answer."); value.addProperty("profile", "local"); value.addProperty("fallback", "fallback"); value.add("dependencies", OsJson.obj("fallback", fallback.digest())); value.add("examples", OsJson.json(List.of(OsJson.obj("input", null, "result", 42))));
			var worker = new SkillDefinition(value);
			var profiles = OsJson.obj("local", OsJson.obj("endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/complete", "model", "fixture", "apiKey", ""));
			try (var workers = new InferenceWorkers(engine, profiles, () -> System.nanoTime() / 1_000_000)) {
				var a = workers.request(worker, fallback, null, "epoch", "basis", null, null);
				var b = workers.request(worker, fallback, null, "epoch", "basis", null, null);
				a.close(); release.complete(null);
				assertEquals(42, b.result().get(10, TimeUnit.SECONDS).get("value").getAsInt());
				assertEquals(1, requests.get()); assertEquals(1, workers.state().get("calls").getAsInt());
				assertEquals(2, workers.state().get("outputTokensCharged").getAsInt()); b.close();
			}
		} finally { server.stop(0); }
	}
}
