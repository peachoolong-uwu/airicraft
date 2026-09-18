package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlannerJsonRepairTest {
	@Test void recursesThroughArrayItemsAndSchemaDeclaredMapValues() {
		var schema = schema("""
			{"type":"object","properties":{"targets":{"type":"array","items":{"type":"object","properties":{"pos":{"type":"object"}}}}},
			 "additionalProperties":{"type":"array","items":{"type":"object"}}}
			""");
		JsonObject target = new JsonObject(); target.addProperty("pos", "{\"x\":1}");
		var targets = new com.google.gson.JsonArray(); targets.add(target.toString());
		JsonObject input = new JsonObject(); input.addProperty("targets", targets.toString()); input.addProperty("a/b~c", "[\"{}\"]");
		var result = PlannerJsonRepair.repair(input, schema);
		assertEquals(java.util.List.of("/targets", "/targets/0", "/targets/0/pos", "/a~1b~0c", "/a~1b~0c/0"), result.paths());
		assertTrue(result.value().getAsJsonObject().getAsJsonArray("targets").get(0).getAsJsonObject().get("pos").isJsonObject());
	}

	@Test void preservesUnrestrictedStringsAndDeclinesAmbiguousObjectAlternatives() {
		for (String definition : java.util.List.of(
			"{\"type\":\"string\"}",
			"{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"object\"}]}",
			"{\"type\":[\"string\",\"object\"]}",
			"{\"oneOf\":[{\"type\":\"object\",\"required\":[\"a\"]},{\"type\":\"object\",\"required\":[\"b\"]}]}")) {
			var input = new JsonPrimitive("{\"a\":1}");
			var result = PlannerJsonRepair.repair(input, schema(definition));
			assertEquals(input, result.value()); assertTrue(result.paths().isEmpty());
		}
	}

	@Test void neverRepairsMalformedJsonOrCoercesScalars() {
		for (String encoded : java.util.List.of("{x:1}", "{'x':1}", "{/*comment*/\"x\":1}", "{\"x\":1} trailing", "null", "true", "42", "\"{}\"")) {
			var input = new JsonPrimitive(encoded);
			var result = PlannerJsonRepair.repair(input, schema("{\"type\":\"object\"}"));
			assertEquals(input, result.value()); assertTrue(result.paths().isEmpty());
		}
	}

	private static JsonObject schema(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
}
