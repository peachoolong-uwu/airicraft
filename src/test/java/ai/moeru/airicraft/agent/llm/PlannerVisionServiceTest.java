package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PlannerVisionServiceTest {
	@Test void usesPlannerEndpointModelAndFreshImageOnlyContextEveryTime() throws Exception {
		var requests = new ArrayList<com.google.gson.JsonObject>();
		var authorizations = new ArrayList<String>();
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/chat/completions", exchange -> {
			requests.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
			authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
			byte[] response = "{\"choices\":[{\"message\":{\"content\":\"A chest beside a door.\"}}]}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.start();
		var config = new AgentConfig.LlmConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
			"planner-key", "planner-model", "http://127.0.0.1:1", "external-key", "external-model",
			15_000, 10_000, 8, 65_536, "low", true);
		try (var service = new PlannerVisionService(config, NoopObservability.INSTANCE)) {
			for (int i = 0; i < 2; i++) assertEquals("A chest beside a door.",
				service.describe(new LlmImageAttachment("image/png", new byte[]{(byte)i}, "low"), "Find chest " + i).get(5, TimeUnit.SECONDS));
			assertEquals(List.of("Bearer planner-key", "Bearer planner-key"), authorizations);
			for (int i = 0; i < requests.size(); i++) {
				var request = requests.get(i);
				assertEquals("planner-model", request.get("model").getAsString());
				assertFalse(request.has("tools"));
				assertFalse(request.has("response_format"));
				assertEquals(2, request.getAsJsonArray("messages").size());
				var content = request.getAsJsonArray("messages").get(1).getAsJsonObject().getAsJsonArray("content");
				assertEquals(2, content.size());
				assertEquals("Find chest " + i, content.get(0).getAsJsonObject().get("text").getAsString());
				assertEquals("image_url", content.get(1).getAsJsonObject().get("type").getAsString());
			}
		} finally { server.stop(0); }
	}

	@Test void countsRawReplayImagesWithSamePrecedenceAsWireSerialization() {
		var raw = JsonParser.parseString("[{\"type\":\"image_url\",\"image_url\":{\"url\":\"x\"}},{\"type\":\"image_url\",\"image_url\":{\"url\":\"y\"}}]");
		var replay = new com.google.gson.JsonObject();
		replay.add("content", raw); replay.addProperty("reasoning_content", "retained reasoning");
		var image = new LlmImageAttachment("image/png", new byte[]{1}, "low");
		var conversation = LlmConversation.of(List.of(
			LlmChatMessage.userWithImage("native", LlmMessageKind.TOOL_RESULT, image),
			new LlmChatMessage("user", "raw wins", LlmMessageKind.TOOL_RESULT, image, raw),
			LlmChatMessage.assistant("replay", OpenAiCompatibleMessageContent.rawMessageForReplay(replay))));
		assertEquals(5, PlannerVisionService.imageCount(conversation));
		assertEquals(0, PlannerVisionService.imageCount(LlmConversation.of(List.of(LlmChatMessage.system("checkpoint")))));
	}
}
