package ai.moeru.airicraft.dashboard;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugDashboardServerTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void servesStaticUiAndKeepsObservationApisBehindTheViewerToken() throws Exception {
		int port = freePort();
		DashboardObservationStore store = new DashboardObservationStore(1024L * 1024L);
		store.startSession("test", 0L, 100L);
		store.append("llm_call", 5L, 150L, Map.of("requestBody", "full prompt", "rawResponseBody", "full response"));
		DebugDashboardServer server = new DebugDashboardServer(store, temporaryDirectory.resolve("latest.log"));
		server.start(new DebugDashboardConfig(true, port, 1, 1024L * 1024L));
		try {
			String baseUrl = "http://127.0.0.1:" + server.status().port();
			String token = server.status().primaryUrl().substring(server.status().primaryUrl().indexOf("#token=") + 7);

			HttpResponse<String> index = send(baseUrl + "/", null);
			HttpResponse<String> unauthorized = send(baseUrl + "/api/bootstrap", null);
			HttpResponse<String> bootstrap = send(baseUrl + "/api/bootstrap", token);
			HttpResponse<String> observations = send(baseUrl + "/api/observations?since=0", token);
			HttpResponse<String> export = send(baseUrl + "/api/export", token);

			assertEquals(200, index.statusCode());
			assertTrue(index.body().contains("Runtime Observatory"));
			assertEquals(401, unauthorized.statusCode());
			assertEquals(200, bootstrap.statusCode());
			assertTrue(JsonParser.parseString(bootstrap.body()).getAsJsonObject().has("sessionId"));
			assertEquals(2, JsonParser.parseString(observations.body()).getAsJsonObject().getAsJsonArray("observations").size());
			assertTrue(export.body().contains("full prompt"));
			assertTrue(export.body().contains("full response"));
		}
		finally {
			server.stop();
		}
	}

	@Test
	void scansUpwardWhenTheConfiguredBasePortIsTaken() throws Exception {
		try (ConsecutivePorts ports = ConsecutivePorts.reserve()) {
			DashboardObservationStore store = new DashboardObservationStore(1024L * 1024L);
			DebugDashboardServer server = new DebugDashboardServer(store, temporaryDirectory.resolve("latest.log"));
			server.start(new DebugDashboardConfig(true, ports.first(), 2, 1024L * 1024L));
			try {
				assertTrue(server.status().running());
				assertEquals(ports.second(), server.status().port());
			}
			finally {
				server.stop();
			}
		}
	}

	@Test
	void seekLoadsPriorStateAndFetchesRgbSeparatelyBehindTheViewerToken() throws Exception {
		DashboardObservationStore store = new DashboardObservationStore(1024L * 1024L);
		store.advanceClock(10L, false, true);
		store.append("runtime_snapshot", 40L, 1L, Map.of("state", "idle"));
		var frame = store.appendFrame(store.sessionId(), 40L, 10L, 1L, Map.of("format", "jpeg", "imageBase64", "AQID"));
		store.advanceClock(20L, false, true);
		store.append("runtime_snapshot", 50L, 2L, Map.of("state", "moving"));
		DebugDashboardServer server = new DebugDashboardServer(store, temporaryDirectory.resolve("latest.log"));
		server.start(new DebugDashboardConfig(true, freePort(), 1, 1024L * 1024L));
		try {
			String baseUrl = "http://127.0.0.1:" + server.status().port();
			String token = server.status().primaryUrl().split("#token=")[1];
			assertEquals(401, send(baseUrl + "/api/recording?at=15", null).statusCode());
			String seek = send(baseUrl + "/api/recording?at=15", token).body();
			assertTrue(seek.contains("idle"));
			assertTrue(!seek.contains("moving") && !seek.contains("AQID"));
			assertEquals(401, send(baseUrl + "/api/frame?sequence=" + frame.sequence(), null).statusCode());
			assertEquals(200, send(baseUrl + "/api/frame?sequence=" + frame.sequence(), token).statusCode());
			assertEquals(404, send(baseUrl + "/api/frame?sequence=999", token).statusCode());
		}
		finally {
			server.stop();
		}
	}

	@Test
	void boundsObservationResponsesByBytesInsteadOfOnlyItemCount() throws Exception {
		int port = freePort();
		DashboardObservationStore store = new DashboardObservationStore(16L * 1024L * 1024L);
		String largePayload = "x".repeat(1024 * 1024);
		for (int index = 0; index < 8; index++) {
			store.append("runtime_snapshot", index, index, Map.of("value", largePayload));
		}
		DebugDashboardServer server = new DebugDashboardServer(store, temporaryDirectory.resolve("latest.log"));
		server.start(new DebugDashboardConfig(true, port, 1, 16L * 1024L * 1024L));
		try {
			String baseUrl = "http://127.0.0.1:" + server.status().port();
			String token = server.status().primaryUrl().substring(server.status().primaryUrl().indexOf("#token=") + 7);

			HttpResponse<String> response = send(baseUrl + "/api/observations?since=0&limit=1000", token);

			assertEquals(200, response.statusCode());
			assertTrue(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 5L * 1024L * 1024L);
			assertTrue(JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("observations").size() < 8);
		}
		finally {
			server.stop();
		}
	}

	private static HttpResponse<String> send(String url, String token) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private record ConsecutivePorts(int first, int second, ServerSocket occupied) implements AutoCloseable {
		private static ConsecutivePorts reserve() throws Exception {
			for (int first = 40_000; first < 60_000; first++) {
				try (ServerSocket secondProbe = new ServerSocket(first + 1)) {
					ServerSocket occupied = new ServerSocket(first);
					return new ConsecutivePorts(first, first + 1, occupied);
				}
				catch (java.io.IOException ignored) {
				}
			}
			throw new IllegalStateException("No consecutive test ports available");
		}

		@Override
		public void close() throws Exception {
			occupied.close();
		}
	}
}
