package ai.moeru.airicraft.wrapper;

import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DriverToolStreamTest {
	@Test void statusDoesNotForwardTheLoadedWorldsRecipeCatalog() throws Exception {
		MinecraftTransport transport = (method, path, body) -> Map.of("codexDriverActive", true, "recipeCatalog", "x".repeat(300000));
		var output = new StringWriter();
		DriverToolStream.serve(transport, new StringReader("{\"id\":\"1\",\"op\":\"status\"}\n"), new PrintWriter(output));
		assertTrue(output.toString().contains("\"codexDriverActive\":true"));
		assertFalse(output.toString().contains("response_limit"));
		assertFalse(output.toString().contains("recipeCatalog"));
	}
	@Test void serialRequestsKeepIdentityAndCannotSelectArbitraryBridgePaths() throws Exception {
		var calls = new ArrayList<String>();
		MinecraftTransport transport = (method, path, body) -> { calls.add(method + " " + path); return Map.of("result", "hello\nworld"); };
		var output = new StringWriter();
		DriverToolStream.serve(transport, new StringReader("""
			{"id":"1","op":"status"}
			{"id":"2","op":"call","name":"inspect_work","arguments":{}}
			{"id":"3","op":"call","name":"inspect_work","arguments":{},"path":"/v1/reload"}
			{"id":"4","op":"call","name":"cancel_work","arguments":{"workId":"JOB:owned","reason":"test"}}
			"""), new PrintWriter(output));
		assertEquals(3, calls.size());
		assertEquals("GET /v1/agent/status", calls.getFirst());
		assertEquals(4, output.toString().lines().count());
		assertTrue(output.toString().contains("\"id\":\"3\",\"ok\":false"));
		assertTrue(output.toString().contains("hello\\nworld"));
	}
	@Test void oversizedRequestIsRejectedBeforeAnyBridgeCall() {
		MinecraftTransport transport = (method, path, body) -> { fail("unexpected bridge call"); return Map.of(); };
		assertThrows(java.io.IOException.class, () -> DriverToolStream.serve(transport,
			new StringReader("a".repeat(65537)), new PrintWriter(new StringWriter())));
	}
}
