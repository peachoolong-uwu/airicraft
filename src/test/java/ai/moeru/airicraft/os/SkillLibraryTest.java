package ai.moeru.airicraft.os;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class SkillLibraryTest {
	@TempDir Path directory;

	static JsonObject definition(String name, String source) {
		var value = OsJson.parse("""
			{"schemaVersion":1,"kind":"behavior","name":"example","description":"A deterministic test skill",
			"tags":[],"capabilities":[],"environment":{},"dependencies":{},"inputContract":true,
			"outputContract":{"type":"integer"},"mode":"generator","source":"",
			"examples":[{"input":null,"responses":[],"expected":[{"done":true,"value":42}]}]}
			""").getAsJsonObject();
		value.addProperty("name", name); value.addProperty("source", source); return value;
	}

	@Test void onlyValidatedImmutableRevisionsCanBeInstalledAfterReopening() throws Exception {
		String digest;
		try (var engine = new GraalSkillEngine()) {
			var library = new SkillLibrary(directory, engine);
			digest = library.propose(definition("answer", "function* main() { return 42; }"), "initial revision");
			assertThrows(IllegalStateException.class, () -> library.resolve(digest, Set.of(), Map.of()));
			assertTrue(library.validate(digest, Set.of(), Map.of()).get("passed").getAsBoolean());
		}
		try (var engine = new GraalSkillEngine()) {
			var reopened = new SkillLibrary(directory, engine);
			assertEquals("answer", reopened.resolve(digest, Set.of(), Map.of()).get(digest).name());
			var altered = reopened.get(digest).value(); altered.addProperty("source", "broken");
			assertEquals(digest, reopened.get(digest).digest());
		}
	}

	@Test void anExampleFailureNeverPromotesARevision() throws Exception {
		try (var engine = new GraalSkillEngine()) {
			var library = new SkillLibrary(directory, engine);
			var digest = library.propose(definition("wrong", "function* main() { return 12; }"), "test mismatch");
			assertFalse(library.validate(digest, Set.of(), Map.of()).get("passed").getAsBoolean());
			assertThrows(IllegalStateException.class, () -> library.resolve(digest, Set.of(), Map.of()));
		}
	}

	@Test void contractRejectsUndeclaredFieldsAndUnknownSchemaKeywords() {
		var contract = new JsonContract(OsJson.parse("""
			{"type":"object","properties":{"count":{"type":"integer","minimum":1}},"required":["count"]}
			"""));
		assertEquals(OsJson.parse("{\"count\":2}"), contract.check(OsJson.parse("{\"count\":2}")));
		assertThrows(IllegalArgumentException.class, () -> contract.check(OsJson.parse("{\"count\":2,\"extra\":true}")));
		assertThrows(IllegalArgumentException.class, () -> new JsonContract(OsJson.parse("{\"type\":\"string\",\"pattern\":\".*\"}")));
	}
}
