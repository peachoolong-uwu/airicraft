package ai.moeru.airicraft.wrapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** A bounded, serial transport, not a scheduler. All calls use the normal bridge tool policy. */
final class DriverToolStream {
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() {};
	static void serve(MinecraftTransport transport, Reader source, PrintWriter out) throws IOException {
		var input = new BufferedReader(source);
		String line;
		while ((line = boundedLine(input)) != null) {
			String id = "";
			try {
				var request = JSON.readValue(line, OBJECT);
				if (!(request.get("id") instanceof String value) || !value.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("invalid_request_id");
				id = value;
				Map<String, Object> payload;
				if ("status".equals(request.get("op")) && Set.of("id", "op").containsAll(request.keySet())) {
					var status = transport.get("/v1/agent/status");
					payload = new LinkedHashMap<>();
					for (String key : Set.of("available", "initialized", "codexDriverActive", "tickCount", "session")) {
						if (status.containsKey(key)) payload.put(key, status.get(key));
					}
				} else if ("call".equals(request.get("op")) && Set.of("id", "op", "name", "arguments").containsAll(request.keySet())) {
					if (!(request.get("name") instanceof String name) || !name.matches("[a-z_]{1,80}") || !(request.get("arguments") instanceof Map<?, ?>)) throw new IllegalArgumentException("invalid_tool_request");
					payload = new LinkedHashMap<>(transport.post("/v1/agent/tools", Map.of(
						"name", name, "arguments", request.get("arguments"), "timeoutMs", 20000)));
					payload.remove("imageBase64");
				} else throw new IllegalArgumentException("unsupported_request");
				reply(out, id, true, payload, "");
			} catch (RuntimeException | IOException error) {
				reply(out, id, false, null, error.getMessage());
			}
		}
	}
	private static String boundedLine(Reader input) throws IOException {
		StringBuilder line = new StringBuilder();
		int ch;
		while ((ch = input.read()) != -1 && ch != '\n') {
			if (line.length() >= 65536) throw new IOException("request_line_limit");
			line.append((char) ch);
		}
		return ch == -1 && line.isEmpty() ? null : line.toString();
	}
	static void reply(PrintWriter out, String id, boolean ok, Object payload, String error) {
		try {
			var response = new LinkedHashMap<String, Object>();
			response.put("id", id);
			response.put("ok", ok);
			if (ok) response.put("payload", payload);
			else response.put("error", error == null ? "stream_error" : error.substring(0, Math.min(error.length(), 2000)));
			String encoded = JSON.writeValueAsString(response);
			if (encoded.length() > 262144) encoded = JSON.writeValueAsString(Map.of("id", id, "ok", false, "error", "response_limit"));
			out.println("response: " + encoded);
			out.flush();
		} catch (IOException errorWriting) { throw new IllegalStateException("response_encoding_failed", errorWriting); }
	}
}
