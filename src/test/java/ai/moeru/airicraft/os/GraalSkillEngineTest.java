package ai.moeru.airicraft.os;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class GraalSkillEngineTest {
	@Test void invalidInputDoesNotConsumeGuestCapacity() throws Exception {
		try (var engine = new GraalSkillEngine()) {
			for (int attempt = 0; attempt < 40; attempt++) assertThrows(IllegalArgumentException.class,
				() -> engine.open("function* main() { return 42; }", "generator", OsJson.json("x".repeat(17_000))));
			try (var valid = engine.open("function* main() { return 42; }", "generator", null)) {
				assertEquals(42, valid.resume(null).get().getAsJsonObject().get("value").getAsInt());
			}
		}
	}
	@Test void independentInstancesSuspendAndResumeOrdinaryJavascriptGenerators() throws Exception {
		String source = """
			function* main(os, input) {
			  const first = yield os.observe({scope: input.scope});
			  return {name: input.name, count: first.count + input.extra};
			}
			""";
		try (var engine = new GraalSkillEngine();
			var a = engine.open(source, "generator", JsonParser.parseString("{\"scope\":\"home\",\"name\":\"a\",\"extra\":2}"));
			var b = engine.open(source, "generator", JsonParser.parseString("{\"scope\":\"home\",\"name\":\"b\",\"extra\":3}"))) {
			assertEquals(JsonParser.parseString("{\"done\":false,\"value\":{\"kind\":\"observe\",\"query\":{\"scope\":\"home\"}}}"), a.resume(null).get());
			assertEquals(JsonParser.parseString("{\"done\":false,\"value\":{\"kind\":\"observe\",\"query\":{\"scope\":\"home\"}}}"), b.resume(null).get());
			assertEquals(JsonParser.parseString("{\"done\":true,\"value\":{\"name\":\"a\",\"count\":6}}"), a.resume(JsonParser.parseString("{\"count\":4}")).get());
			assertEquals(JsonParser.parseString("{\"done\":true,\"value\":{\"name\":\"b\",\"count\":12}}"), b.resume(JsonParser.parseString("{\"count\":9}")).get());
		}
	}

	@Test void offersAreCopiedAndHaveNoHostAuthority() throws Exception {
		try (var engine = new GraalSkillEngine(); var skill = engine.open("""
			function offers(os, input, view) {
			  let denied = false;
			  try { Java.type('java.lang.System'); } catch (_) { denied = true; }
			  return [os.work('probe', {denied, process: typeof process, require: typeof require,
			    frozen: Object.isFrozen(view), number: input.number + view.number})];
			}
			""", "offers", OsJson.parse("{\"number\":2}"))) {
			var result = skill.offers(OsJson.parse("{\"number\":3}")).get().getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("arguments");
			assertTrue(result.get("denied").getAsBoolean());
			assertEquals("undefined", result.get("process").getAsString());
			assertEquals("undefined", result.get("require").getAsString());
			assertTrue(result.get("frozen").getAsBoolean());
			assertEquals(5, result.get("number").getAsInt());
		}
	}

	@Test void runawayGuestCannotBlockAnotherSkill() throws Exception {
		try (var engine = new GraalSkillEngine();
			var runaway = engine.open("function* main() { while (true) {} }", "generator", null);
			var healthy = engine.open("function* main() { return 42; }", "generator", null)) {
			var running = runaway.resume(null);
			assertEquals(42, healthy.resume(null).get().getAsJsonObject().get("value").getAsInt());
			assertThrows(ExecutionException.class, running::get);
		}
	}

	@Test void aSkillCannotQueueConcurrentResumes() throws Exception {
		try (var engine = new GraalSkillEngine();
			var skill = engine.open("function* main() { while (true) {} }", "generator", null)) {
			var first = skill.resume(null);
			var second = skill.resume(null);
			assertEquals("skill_busy", assertThrows(ExecutionException.class, second::get).getCause().getMessage());
			skill.close();
			assertThrows(ExecutionException.class, first::get);
		}
	}
}
