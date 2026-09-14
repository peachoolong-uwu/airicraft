package ai.moeru.airicraft.agent.llm.codex;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexAppServerClientTest {
	@TempDir
	Path tempDir;

	@Test
	void initializesAndStreamsStructuredTurnCompletion() throws Exception {
		Path log = tempDir.resolve("fake-app-server.log");
		try (CodexAppServerClient client = fakeClient(log)) {
			JsonObject threadParams = new JsonObject();
			threadParams.addProperty("ephemeral", true);
			JsonObject thread = client.request("thread/start", threadParams, 2_000);
			String threadId = thread.getAsJsonObject("thread").get("id").getAsString();
			assertEquals("root", threadId);

			JsonObject turnParams = new JsonObject();
			turnParams.addProperty("threadId", threadId);
			turnParams.add("input", JsonParser.parseString("[{\"type\":\"text\",\"text\":\"hello\"}]").getAsJsonArray());
			turnParams.add("outputSchema", new JsonObject());
			CodexAppServerClient.TurnHandle handle = client.startTurn(turnParams, 2_000);
			CodexAppServerClient.TurnResult result = handle.completion().get(2, TimeUnit.SECONDS);

			assertEquals("completed", result.status());
			assertTrue(result.agentMessage().contains("chatMessages"));
			assertTrue(result.agentMessage().contains(threadId));
			client.archiveThread(threadId, 2_000);
		}

		String wireLog = Files.readString(log);
		assertTrue(wireLog.contains("initialize"));
		assertTrue(wireLog.contains("initialize experimentalApi true"));
		assertTrue(wireLog.contains("initialized"));
		assertTrue(wireLog.contains("thread/start"));
		assertTrue(wireLog.contains("turn/start"));
		assertTrue(wireLog.contains("thread/archive root"));
		assertFalse(wireLog.contains("thread/fork"));
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "AIRICRAFT_CODEX_LIVE", matches = "1")
	void interruptsThenContinuesSameThreadThroughInstalledAppServer() throws Exception {
		String executable = System.getenv().getOrDefault("AIRICRAFT_CODEX_EXECUTABLE", "codex");
		try (CodexAppServerClient client = new CodexAppServerClient(executable, 10_000)) {
			String threadId = client.request("thread/start", liveThreadParams(), 10_000)
				.getAsJsonObject("thread")
				.get("id")
				.getAsString();
			try {
				CodexAppServerClient.TurnHandle superseded = client.startTurn(
					liveTurnParams(threadId, "Think extensively before replying with exactly: stale"),
					10_000
				);
				client.interrupt(threadId, superseded.turnId()).get(10, TimeUnit.SECONDS);
				assertEquals("interrupted", superseded.completion().get(120, TimeUnit.SECONDS).status());

				CodexAppServerClient.TurnHandle replacement = client.startTurn(
					liveTurnParams(threadId, "Reply with exactly: ready"),
					10_000
				);
				CodexAppServerClient.TurnResult result = replacement.completion().get(120, TimeUnit.SECONDS);
				assertEquals("completed", result.status());
				assertTrue(result.agentMessage().trim().equalsIgnoreCase("ready"));
			}
			finally {
				client.archiveThread(threadId, 10_000);
			}
		}
	}

	@Test
	void interruptRequestCompletesWaitingTurnAsInterrupted() throws Exception {
		Path log = tempDir.resolve("interrupt.log");
		try (CodexAppServerClient client = fakeClient(log)) {
			JsonObject threadParams = new JsonObject();
			threadParams.addProperty("ephemeral", true);
			client.request("thread/start", threadParams, 2_000);

			JsonObject turnParams = new JsonObject();
			turnParams.addProperty("threadId", "root");
			turnParams.add("input", JsonParser.parseString("[{\"type\":\"text\",\"text\":\"WAIT DELAY_START_RESPONSE\"}]").getAsJsonArray());
			CodexAppServerClient.TurnHandle handle = client.startTurn(turnParams, 2_000);

			client.interrupt(handle.threadId(), handle.turnId()).get(2, TimeUnit.SECONDS);
			CodexAppServerClient.TurnResult result = handle.completion().get(2, TimeUnit.SECONDS);
			assertEquals("interrupted", result.status());
		}

		assertTrue(Files.readString(log).contains("turn/interrupt"));
	}

	static CodexAppServerClient fakeClient(Path log) {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		return new CodexAppServerClient(List.of(
			java,
			"-Dairicraft.fake.codex.log=" + log.toAbsolutePath(),
			"-cp",
			System.getProperty("java.class.path"),
			FakeAppServer.class.getName()
		), 2_000);
	}

	private static JsonObject liveThreadParams() {
		JsonObject params = new JsonObject();
		params.addProperty("approvalPolicy", "never");
		params.addProperty("sandbox", "read-only");
		params.addProperty("cwd", Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().toString());
		params.addProperty("baseInstructions", "Return only a final response.");
		return params;
	}

	private static JsonObject liveTurnParams(String threadId, String text) {
		JsonObject params = new JsonObject();
		params.addProperty("threadId", threadId);
		params.add("input", JsonParser.parseString("[{\"type\":\"text\",\"text\":" + new com.google.gson.Gson().toJson(text) + "}]").getAsJsonArray());
		params.addProperty("approvalPolicy", "never");
		return params;
	}

	public static final class FakeAppServer {
		private FakeAppServer() {
		}

		public static void main(String[] args) throws Exception {
			Path log = Path.of(System.getProperty("airicraft.fake.codex.log"));
			int turnCount = 0;
			JsonElement delayedTurnStartId = null;
			try (
				BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
				BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))
			) {
				String line;
				while ((line = reader.readLine()) != null) {
					JsonObject request = JsonParser.parseString(line).getAsJsonObject();
					String method = request.get("method").getAsString();
					JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
					String suffix = switch (method) {
						case "thread/archive" -> " " + params.get("threadId").getAsString();
						default -> "";
					};
					append(log, method + suffix);
					if (params.has("serviceTier")) {
						append(log, method + " serviceTier " + params.get("serviceTier").getAsString());
					}
					if ("initialize".equals(method) && params.has("capabilities")) {
						append(log, "initialize experimentalApi "
							+ params.getAsJsonObject("capabilities").get("experimentalApi").getAsBoolean());
					}
					if ("turn/start".equals(method) && params.has("effort")) {
						append(log, "turn/start effort " + params.get("effort").getAsString());
					}
					if ("turn/start".equals(method) && params.has("additionalContext")) {
						append(log, "turn/start additionalContext " + params.get("additionalContext"));
					}
					if (!request.has("id")) {
						continue;
					}

					JsonElement id = request.get("id");
					switch (method) {
						case "initialize" -> respond(writer, id, new JsonObject());
						case "thread/start" -> respond(writer, id, threadResponse("root"));
						case "turn/start" -> {
							turnCount++;
							String turnId = "turn-" + turnCount;
							String threadId = params.get("threadId").getAsString();
							String input = params.get("input").toString();
							notifyTurnStarted(writer, threadId, turnId);
							if (input.contains("DELAY_START_RESPONSE")) {
								delayedTurnStartId = id.deepCopy();
							}
							else {
								respond(writer, id, turnResponse(turnId));
							}
							if (!input.contains("WAIT")) {
								completeTurn(writer, threadId, turnId, "completed", structuredReply(threadId, input));
							}
						}
						case "turn/interrupt" -> {
							respond(writer, id, new JsonObject());
							completeTurn(
								writer,
								params.get("threadId").getAsString(),
								params.get("turnId").getAsString(),
								"interrupted",
								null
							);
							if (delayedTurnStartId != null) {
								respond(writer, delayedTurnStartId, turnResponse(params.get("turnId").getAsString()));
								delayedTurnStartId = null;
							}
						}
						default -> respond(writer, id, new JsonObject());
					}
				}
			}
		}

		private static JsonObject turnResponse(String turnId) {
			JsonObject result = new JsonObject();
			JsonObject turn = new JsonObject();
			turn.addProperty("id", turnId);
			turn.addProperty("status", "inProgress");
			turn.add("items", new com.google.gson.JsonArray());
			result.add("turn", turn);
			return result;
		}

		private static void notifyTurnStarted(BufferedWriter writer, String threadId, String turnId) throws Exception {
			JsonObject params = new JsonObject();
			params.addProperty("threadId", threadId);
			params.add("turn", turnResponse(turnId).getAsJsonObject("turn"));
			notify(writer, "turn/started", params);
		}

		private static JsonObject threadResponse(String threadId) {
			JsonObject result = new JsonObject();
			JsonObject thread = new JsonObject();
			thread.addProperty("id", threadId);
			result.add("thread", thread);
			result.addProperty("model", "fake-codex");
			return result;
		}

		private static String structuredReply(String threadId, String input) {
			JsonObject message = new JsonObject();
			if (input.contains("REQUEST_TOOL")) {
				message.add("chatMessages", new com.google.gson.JsonArray());
				com.google.gson.JsonArray tools = new com.google.gson.JsonArray();
				JsonObject tool = new JsonObject();
				tool.addProperty("name", "clear_goal");
				tool.addProperty("argumentsJson", "{}");
				tools.add(tool);
				message.add("toolCalls", tools);
				return message.toString();
			}
			com.google.gson.JsonArray chats = new com.google.gson.JsonArray();
			JsonObject chat = new JsonObject();
			chat.addProperty("text", "from:" + threadId);
			chat.addProperty("delayTicks", 0);
			chats.add(chat);
			message.add("chatMessages", chats);
			message.add("toolCalls", new com.google.gson.JsonArray());
			return message.toString();
		}

		private static void completeTurn(BufferedWriter writer, String threadId, String turnId, String status, String text) throws Exception {
			if (text != null) {
				JsonObject item = new JsonObject();
				item.addProperty("id", "item-" + turnId);
				item.addProperty("type", "agentMessage");
				item.addProperty("text", text);
				JsonObject itemParams = new JsonObject();
				itemParams.addProperty("threadId", threadId);
				itemParams.addProperty("turnId", turnId);
				itemParams.addProperty("completedAtMs", 1L);
				itemParams.add("item", item);
				notify(writer, "item/completed", itemParams);
			}

			JsonObject turn = new JsonObject();
			turn.addProperty("id", turnId);
			turn.addProperty("status", status);
			turn.add("items", new com.google.gson.JsonArray());
			JsonObject completedParams = new JsonObject();
			completedParams.addProperty("threadId", threadId);
			completedParams.add("turn", turn);
			notify(writer, "turn/completed", completedParams);
		}

		private static void respond(BufferedWriter writer, JsonElement id, JsonObject result) throws Exception {
			JsonObject response = new JsonObject();
			response.add("id", id);
			response.add("result", result);
			write(writer, response);
		}

		private static void notify(BufferedWriter writer, String method, JsonObject params) throws Exception {
			JsonObject notification = new JsonObject();
			notification.addProperty("method", method);
			notification.add("params", params);
			write(writer, notification);
		}

		private static void write(BufferedWriter writer, JsonObject message) throws Exception {
			writer.write(message.toString());
			writer.newLine();
			writer.flush();
		}

		private static void append(Path log, String line) throws Exception {
			Files.writeString(
				log,
				line + System.lineSeparator(),
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.APPEND
			);
		}
	}
}
