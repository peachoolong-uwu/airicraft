package ai.moeru.airicraft.agent.llm;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CaveMapToolProviderTest {
	@Test void boundedReadOnlyGeometryQuery() {
		var p = new CaveMapToolProvider();
		assertTrue(p.isReadTool("map_cave"));
		p.validateArguments("map_cave", JsonParser.parseString("{\"radius\":32,\"verticalRadius\":24,\"target\":{\"x\":1,\"y\":-20,\"z\":4}}").getAsJsonObject());
		for (String json : new String[]{"{\"radius\":33}","{\"radius\":8.5}","{\"verticalRadius\":25}","{\"opennessWeight\":3}","{\"target\":{\"x\":2}}"})
			assertThrows(JsonParseException.class, () -> p.validateArguments("map_cave", JsonParser.parseString(json).getAsJsonObject()));
	}
}
