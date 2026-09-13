package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.AgentConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleLlmBackendTest {
	@Test void independentProfilesKeepTheirCacheIdentityAndEffortOnRepeatedRequests() throws Exception {
		var bodyRef = new AtomicReference<String>();
		try (TestServer server = TestServer.start(bodyRef, plaintextResponse("Ready."))) {
			var tools = PlannerToolRegistry.of();
			var controller = new OpenAiCompatibleLlmBackend(config(server.port(), false).forRole("controller", "none"),
				ai.moeru.airicraft.agent.observability.NoopObservability.INSTANCE, tools, "test:controller");
			var thinker = new OpenAiCompatibleLlmBackend(config(server.port(), false).forRole("thinker", "medium"),
				ai.moeru.airicraft.agent.observability.NoopObservability.INSTANCE, tools, "test:thinking");
			for (int pass = 0; pass < 2; pass++) for (var backend : List.of(controller, thinker)) {
				backend.generate(LlmConversation.of(List.of(LlmChatMessage.user("Ready?", LlmMessageKind.USER_TURN))));
				var body = JsonParser.parseString(bodyRef.get()).getAsJsonObject();
				assertEquals(backend == controller ? "none" : "medium", body.get("reasoning_effort").getAsString());
				assertEquals(backend == controller ? "test:controller" : "test:thinking", body.get("prompt_cache_key").getAsString());
			}
		}
	}

	@Test
	void sendsConfiguredReasoningEffortAndOmitsProviderDefault() throws Exception {
		for (String effort : List.of("", "low", "none")) {
			AtomicReference<String> bodyRef = new AtomicReference<>();
			try (TestServer server = TestServer.start(bodyRef, plaintextResponse("Ready."))) {
				var c = config(server.port(), false);
				var configured = new AgentConfig.LlmConfig(c.providerBaseUrl(), c.apiKey(), c.model(),
					c.visionProviderBaseUrl(), c.visionApiKey(), c.visionModel(), c.requestTimeoutMillis(),
					c.visionRequestTimeoutMillis(), c.maxRecentConversationTurns(), c.plannerCompactionTriggerTokens(),
					c.plannerPendingSemanticEventCap(), c.plannerSessionMaxConcurrentAttempts(), c.plannerSessionCoalesceStepMillis(),
					c.plannerSessionCoalesceMinMillis(), c.plannerSessionCoalesceMaxMillis(), c.visionImageDetail(),
					c.plannerNativeVisionEnabled(), c.plannerUseJsonObjectResponseFormat(), c.plannerBackend(), c.codexAppServer(), effort);
				new OpenAiCompatibleLlmBackend(configured).generate(LlmConversation.of(List.of(LlmChatMessage.user("Ready?", LlmMessageKind.USER_TURN))));
				var body = JsonParser.parseString(bodyRef.get()).getAsJsonObject();
				if (effort.isEmpty()) assertFalse(body.has("reasoning_effort"));
				else assertEquals(effort, body.get("reasoning_effort").getAsString());
			}
		}
	}

	@Test
	void generateClassifiesConnectionFailureAsProviderUnavailable() throws Exception {
		int closedPort = closedLocalPort();
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(closedPort, false));

		LlmBackendException exception = assertThrows(LlmBackendException.class, () ->
			backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent status", LlmMessageKind.USER_TURN)
			)))
		);

		assertEquals(LlmFailureType.PROVIDER_UNAVAILABLE, exception.failureType());
		assertTrue(exception.getMessage().contains("LLM request failed:"));
		assertTrue(exception.getMessage().contains("ConnectException"));
	}

	@Test
	void generateParsesPlaintextAndRequestsToolsWithoutResponseFormat() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, plaintextResponse("I'll keep watch."))) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(server.port(), true));

			LlmCallResult<PlannerResponse> result = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent status", LlmMessageKind.USER_TURN)
			)));

			assertEquals("I'll keep watch.", result.payload().replyText());
			assertNull(result.payload().toolCall());
			assertEquals("reply_only", result.payload().intent().type());
			assertEquals(Integer.valueOf(1234), result.usage().promptTokens());
			assertEquals(Integer.valueOf(56), result.usage().completionTokens());
			assertEquals(Integer.valueOf(1290), result.usage().totalTokens());

			JsonObject body = JsonParser.parseString(bodyRef.get()).getAsJsonObject();
			assertFalse(body.has("response_format"));
			assertEquals("auto", body.get("tool_choice").getAsString());
			JsonArray tools = body.getAsJsonArray("tools");
			assertNotNull(tools);
			assertEquals(5, tools.size());
			assertEquals(PlannerToolCatalog.DISCOVER_TOOLS, tools.get(0).getAsJsonObject()
				.getAsJsonObject("function").get("name").getAsString());
			assertEquals(PlannerToolCatalog.START_ACTION_GOAL, tools.get(1).getAsJsonObject()
				.getAsJsonObject("function").get("name").getAsString());
			JsonObject discoverSchema = tools.get(0).getAsJsonObject()
				.getAsJsonObject("function")
				.getAsJsonObject("parameters")
				.getAsJsonObject("properties")
				.getAsJsonObject("query");
			assertEquals("string", discoverSchema.get("type").getAsString());
			assertEquals("planner-model", body.get("model").getAsString());
		}
	}

	@Test
	void generateParsesSingleToolCallWithNarration() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, toolCallResponse(
			"call_nav",
			"navigate_to",
			"{\\\"x\\\":12,\\\"y\\\":64,\\\"z\\\":-8,\\\"exactY\\\":true,\\\"narration\\\":\\\"I'm going there\\\"}"
		))) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(server.port(), false));

			LlmCallResult<PlannerResponse> result = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent go to 12 64 -8", LlmMessageKind.USER_TURN)
			)));

			PlannerToolCall toolCall = result.payload().toolCall();
			assertNotNull(toolCall);
			assertEquals("call_nav", toolCall.id());
			assertEquals("navigate_to", toolCall.name());
			assertEquals("I'm going there", toolCall.narration());
			assertEquals(12, toolCall.arguments().get("x").getAsInt());
			assertEquals(64, toolCall.arguments().get("y").getAsInt());
			assertEquals(-8, toolCall.arguments().get("z").getAsInt());
			assertTrue(toolCall.arguments().get("exactY").getAsBoolean());
			assertEquals("", result.payload().replyText());
		}
	}

	@Test
	void chatClientUsesTheCurrentSurfaceOnEachPlannerRequest() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		PlannerToolRegistry registry = PlannerToolRegistry.of();
		try (TestServer server = TestServer.start(bodyRef, plaintextResponse("Done."))) {
			OpenAiCompatibleChatClient client = new OpenAiCompatibleChatClient(config(server.port(), false), registry);
			LlmConversation conversation = LlmConversation.of(List.of(LlmChatMessage.system("system")));

			client.complete(conversation, LlmRequestOptions.planner());
			List<String> initialToolNames = toolNames(JsonParser.parseString(bodyRef.get()).getAsJsonObject().getAsJsonArray("tools"));
			assertEquals(List.of(
				PlannerToolCatalog.DISCOVER_TOOLS,
				PlannerToolCatalog.START_ACTION_GOAL,
				PlannerToolCatalog.INSPECT_ACTION_GOAL,
				PlannerToolCatalog.CANCEL_ACTION_GOAL,
				PlannerToolCatalog.CLEAR_GOAL
			), initialToolNames);

			registry.discoverTools("navigation", 3);
			client.complete(conversation, LlmRequestOptions.planner());
			List<String> discoveredToolNames = toolNames(JsonParser.parseString(bodyRef.get()).getAsJsonObject().getAsJsonArray("tools"));
			assertTrue(discoveredToolNames.contains(PlannerToolCatalog.NAVIGATE_TO));
			assertFalse(discoveredToolNames.contains(PlannerToolCatalog.CRAFT_RECIPE));
		}
	}

	@Test
	void generateRejectsUnknownToolCall() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, toolCallResponse("call_bad", "dance", "{}"))) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(server.port(), false));

			LlmBackendException exception = assertThrows(LlmBackendException.class, () ->
				backend.generate(LlmConversation.of(List.of(LlmChatMessage.system("system"))))
			);

			assertEquals(LlmFailureType.PARSE_ERROR, exception.failureType());
		}
	}

	@Test
	void generateRejectsInvalidToolArguments() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, toolCallResponse(
			"call_collect",
			"collect_resource",
			"{\\\"resourceKind\\\":\\\"WOOD_LOGS\\\",\\\"quantity\\\":0}"
		))) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(server.port(), false));

			LlmBackendException exception = assertThrows(LlmBackendException.class, () ->
				backend.generate(LlmConversation.of(List.of(LlmChatMessage.system("system"))))
			);

			assertEquals(LlmFailureType.PARSE_ERROR, exception.failureType());
		}
	}

	@Test
	void generateParsesMultipleReadToolCalls() throws Exception {
		String responseBody = """
			{
			  "choices": [
			    {
			      "message": {
			        "content": null,
			        "tool_calls": [
			          {"id":"call_1","type":"function","function":{"name":"inspect_inventory","arguments":"{}"}},
			          {"id":"call_2","type":"function","function":{"name":"check_craftables","arguments":"{}"}}
			        ]
			      }
			    }
			  ]
			}
			""";
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, responseBody)) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(server.port(), false));

			LlmCallResult<PlannerResponse> result = backend.generate(LlmConversation.of(List.of(LlmChatMessage.system("system"))));

		assertEquals(List.of("inspect_inventory", "check_craftables"), result.payload().toolCalls().stream()
			.map(PlannerToolCall::name)
			.toList());
		assertEquals("", result.payload().replyText());
		assertEquals("inspect_inventory", result.payload().toolCall().name());
	}
	}

	@Test
	void generateParsesMixedReadAndEntityActionToolCalls() throws Exception {
		String responseBody = """
			{
			  "choices": [
			    {
			      "message": {
			        "content": null,
			        "tool_calls": [
			          {"id":"call_scan","type":"function","function":{"name":"inspect_nearby_entities","arguments":"{\\"prompt\\":\\"Check nearby mobs\\"}"}},
			          {"id":"call_attack","type":"function","function":{"name":"attack_entity","arguments":"{\\"uuid\\":\\"slime-1\\"}"}}
			        ]
			      }
			    }
			  ],
			  "usage": {
			    "prompt_tokens": 1234,
			    "completion_tokens": 56,
			    "total_tokens": 1290
			  }
			}
			""";
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, responseBody)) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(server.port(), false));

			LlmCallResult<PlannerResponse> result = backend.generate(LlmConversation.of(List.of(LlmChatMessage.system("system"))));

			assertNotNull(result.payload().toolCall());
			assertEquals("attack_entity", result.payload().toolCall().name());
			assertEquals(1, result.payload().toolCalls().size());
			assertEquals("slime-1", result.payload().toolCall().arguments().get("uuid").getAsString());
		}
	}

	@Test
	void generateRejectsMultipleActionToolCallsBeforeExecutionValidation() throws Exception {
		String responseBody = """
			{
			  "choices": [
			    {
			      "message": {
			        "content": null,
			        "tool_calls": [
			          {"id":"call_attack","type":"function","function":{"name":"attack_entity","arguments":"{\\"uuid\\":\\"slime-1\\"}"}},
			          {"id":"call_clear","type":"function","function":{"name":"clear_goal","arguments":"{}"}}
			        ]
			      }
			    }
			  ],
			  "usage": {
			    "prompt_tokens": 1234,
			    "completion_tokens": 56,
			    "total_tokens": 1290
			  }
			}
			""";
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, responseBody)) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(server.port(), false));

			LlmBackendException exception = assertThrows(LlmBackendException.class, () ->
				backend.generate(LlmConversation.of(List.of(LlmChatMessage.system("system"))))
			);

			assertEquals(LlmFailureType.PARSE_ERROR, exception.failureType());
			assertTrue(exception.getMessage().contains("attack_entity"));
			assertTrue(exception.getMessage().contains("clear_goal"));
		}
	}

	@Test
	void generateParsesStructuredChatMessages() throws Exception {
		String content = "{\\\"chatMessages\\\":[{\\\"text\\\":\\\"I found the cave.\\\",\\\"delayTicks\\\":0},{\\\"text\\\":\\\"I will head back now.\\\",\\\"delaySeconds\\\":1.5}]}";
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, plaintextResponse(content))) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(server.port(), false));

			LlmCallResult<PlannerResponse> result = backend.generate(LlmConversation.of(List.of(LlmChatMessage.system("system"))));

			assertEquals(2, result.payload().chatMessages().size());
			assertEquals("I found the cave.", result.payload().chatMessages().get(0).text());
			assertEquals(0, result.payload().chatMessages().get(0).delayTicks());
			assertEquals("I will head back now.", result.payload().chatMessages().get(1).text());
			assertEquals(30, result.payload().chatMessages().get(1).delayTicks());
			assertEquals("I found the cave. I will head back now.", result.payload().replyText());
			assertNull(result.payload().toolCall());
		}
	}

	@Test
	void generateRejectsReadAndActionToolCallBatch() throws Exception {
		String responseBody = """
			{
			  "choices": [
			    {
			      "message": {
			        "content": null,
			        "tool_calls": [
			          {"id":"call_check","type":"function","function":{"name":"check_craftables","arguments":"{}"}},
			          {"id":"call_craft","type":"function","function":{"name":"craft_recipe","arguments":"{\\"recipeId\\":\\"minecraft:stick\\",\\"times\\":1}"}}
			        ]
			      }
			    }
			  ]
			}
			""";
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, responseBody)) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(server.port(), false));

			LlmBackendException exception = assertThrows(LlmBackendException.class, () ->
				backend.generate(LlmConversation.of(List.of(LlmChatMessage.system("system"))))
			);

			assertEquals(LlmFailureType.PARSE_ERROR, exception.failureType());
			assertTrue(exception.getMessage().contains("check_craftables"));
			assertTrue(exception.getMessage().contains("craft_recipe"));
		}
	}

	@Test
	void generateTreatsLegacyJsonContentAsPlaintext() throws Exception {
		String legacyJson = "{\"replyText\":\"old\",\"intent\":{\"type\":\"set_goal\"}}";
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, plaintextResponse(legacyJson.replace("\"", "\\\"")))) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(config(server.port(), false));

			LlmCallResult<PlannerResponse> result = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system")
			)));

			assertEquals(legacyJson, result.payload().replyText());
			assertNull(result.payload().toolCall());
			assertEquals("reply_only", result.payload().intent().type());
		}
	}

	@Test
	void chatClientCompactionKeepsJsonResponseFormatAndOmitsTools() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, plaintextResponse("{}"))) {
			OpenAiCompatibleChatClient chatClient = new OpenAiCompatibleChatClient(
				config(server.port(), false).forRole("thinker", "medium"),
				ai.moeru.airicraft.agent.observability.NoopObservability.INSTANCE,
				PlannerToolRegistry.empty(), "test:thinking:compaction");

			chatClient.complete(
				LlmConversation.of(List.of(LlmChatMessage.user("COMPACTION TASK:", LlmMessageKind.TASK))),
				LlmRequestOptions.compaction()
			);

				JsonObject body = JsonParser.parseString(bodyRef.get()).getAsJsonObject();
				assertEquals("json_object", body.getAsJsonObject("response_format").get("type").getAsString());
				assertEquals("none", body.get("reasoning_effort").getAsString());
				assertEquals("test:thinking:compaction", body.get("prompt_cache_key").getAsString());
				assertFalse(body.has("tools"));
				assertFalse(body.has("tool_choice"));
			}
		}

	@Test
	void chatClientSerializesToolResultMessages() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, plaintextResponse("Done."))) {
			OpenAiCompatibleChatClient chatClient = new OpenAiCompatibleChatClient(config(server.port(), false));
			JsonObject args = new JsonObject();
			args.addProperty("narration", "I'm checking inventory");
			PlannerToolCall toolCall = new PlannerToolCall("call_inv", "inspect_inventory", args, "I'm checking inventory", null);

			chatClient.complete(
				LlmConversation.of(List.of(
					LlmChatMessage.assistantToolCall("", toolCall),
					LlmChatMessage.tool("call_inv", "Tool result for inspect_inventory: empty")
				)),
				LlmRequestOptions.planner()
			);

			JsonArray messages = JsonParser.parseString(bodyRef.get()).getAsJsonObject().getAsJsonArray("messages");
			JsonObject assistant = messages.get(0).getAsJsonObject();
			JsonObject tool = messages.get(1).getAsJsonObject();
			assertEquals("assistant", assistant.get("role").getAsString());
			assertTrue(assistant.has("tool_calls"));
			assertEquals("tool", tool.get("role").getAsString());
			assertEquals("call_inv", tool.get("tool_call_id").getAsString());
		}
	}

	@Test
	void chatClientReplaysReasoningContentOnAssistantMessages() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, plaintextResponse("Done."))) {
			OpenAiCompatibleChatClient chatClient = new OpenAiCompatibleChatClient(config(server.port(), false));
			JsonObject rawAssistant = new JsonObject();
			rawAssistant.addProperty("content", "Visible reply.");
			rawAssistant.addProperty("reasoning_content", "private chain");

			chatClient.complete(
				LlmConversation.of(List.of(
					LlmChatMessage.assistant("Visible reply.", OpenAiCompatibleMessageContent.rawMessageForReplay(rawAssistant))
				)),
				LlmRequestOptions.planner()
			);

			JsonObject assistant = JsonParser.parseString(bodyRef.get()).getAsJsonObject()
				.getAsJsonArray("messages")
				.get(0)
				.getAsJsonObject();
			assertEquals("assistant", assistant.get("role").getAsString());
			assertEquals("Visible reply.", assistant.get("content").getAsString());
			assertEquals("private chain", assistant.get("reasoning_content").getAsString());
		}
	}

	@Test
	void chatClientReplaysReasoningContentOnAssistantToolCalls() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, plaintextResponse("Done."))) {
			OpenAiCompatibleChatClient chatClient = new OpenAiCompatibleChatClient(config(server.port(), false));
			JsonObject rawAssistant = new JsonObject();
			rawAssistant.add("content", JsonNull.INSTANCE);
			rawAssistant.addProperty("reasoning_content", "private chain");
			JsonObject args = new JsonObject();
			PlannerToolCall toolCall = new PlannerToolCall("call_inv", "inspect_inventory", args, "", null);

			chatClient.complete(
				LlmConversation.of(List.of(
					LlmChatMessage.assistantToolCall("", toolCall, OpenAiCompatibleMessageContent.rawMessageForReplay(rawAssistant)),
					LlmChatMessage.tool("call_inv", "Tool result for inspect_inventory: empty")
				)),
				LlmRequestOptions.planner()
			);

			JsonObject assistant = JsonParser.parseString(bodyRef.get()).getAsJsonObject()
				.getAsJsonArray("messages")
				.get(0)
				.getAsJsonObject();
			assertEquals("assistant", assistant.get("role").getAsString());
			assertTrue(!assistant.has("content") || assistant.get("content").isJsonNull());
			assertEquals("private chain", assistant.get("reasoning_content").getAsString());
			assertTrue(assistant.has("tool_calls"));
		}
	}

	private static AgentConfig.LlmConfig config(int port, boolean jsonResponseFormatFlag) {
		return new AgentConfig.LlmConfig(
			"http://127.0.0.1:" + port,
			"planner-key",
			"planner-model",
			"https://api.openai.com/v1",
			"",
			"",
			15_000,
			10_000,
			8,
			65_536,
			"low",
			false,
			jsonResponseFormatFlag
		);
	}

	private static String plaintextResponse(String content) {
		return """
			{
			  "choices": [
			    {
			      "message": {
			        "content": "%s"
			      }
			    }
			  ],
			  "usage": {
			    "prompt_tokens": 1234,
			    "completion_tokens": 56,
			    "total_tokens": 1290
			  }
			}
			""".formatted(content);
	}

	private static String toolCallResponse(String id, String name, String arguments) {
		return """
			{
			  "choices": [
			    {
			      "message": {
			        "content": null,
			        "tool_calls": [
			          {
			            "id": "%s",
			            "type": "function",
			            "function": {
			              "name": "%s",
			              "arguments": "%s"
			            }
			          }
			        ]
			      }
			    }
			  ],
			  "usage": {
			    "prompt_tokens": 1234,
			    "completion_tokens": 56,
			    "total_tokens": 1290
			  }
			}
			""".formatted(id, name, arguments);
	}

	private static int closedLocalPort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
			return socket.getLocalPort();
		}
	}

	private static List<String> toolNames(JsonArray tools) {
		return tools.asList().stream()
			.map(tool -> tool.getAsJsonObject().getAsJsonObject("function").get("name").getAsString())
			.toList();
	}

	private static final class TestServer implements AutoCloseable {
		private final HttpServer server;
		private final int port;

		private TestServer(HttpServer server, int port) {
			this.server = server;
			this.port = port;
		}

		static TestServer start(AtomicReference<String> bodyRef, String responseBody) throws IOException {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
			server.createContext("/chat/completions", exchange -> handle(exchange, bodyRef, responseBody));
			server.setExecutor(Executors.newSingleThreadExecutor());
			server.start();
			return new TestServer(server, server.getAddress().getPort());
		}

		private static void handle(HttpExchange exchange, AtomicReference<String> bodyRef, String responseBody) throws IOException {
			bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		}

		int port() {
			return port;
		}

		@Override
		public void close() {
			server.stop(0);
		}
	}
}
