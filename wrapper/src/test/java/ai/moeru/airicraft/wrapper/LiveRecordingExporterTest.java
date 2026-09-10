package ai.moeru.airicraft.wrapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LiveRecordingExporterTest {
	@TempDir Path directory;

	@Test
	void freezesTheRangeAndUpperCursorWhileStreamingPages() throws Exception {
		List<String> paths = new ArrayList<>();
		MinecraftTransport transport = (method, path, body) -> {
			paths.add(path);
			return page("session", paths.size() == 1);
		};
		Path output = directory.resolve("incident.jsonl");
		var result = new LiveRecordingExporter(transport, new ObjectMapper()).export(output,
			"/v1/agent/debug/recording?mode=query&since=0&limit=1", true);
		assertEquals(2L, result.get("observations"));
		assertEquals(2, paths.size());
		assertTrue(paths.getLast().contains("since=4"));
		assertTrue(paths.getLast().endsWith("&from=20&to=50&through=9"));
		List<String> lines = Files.readAllLines(output);
		assertTrue(lines.getFirst().contains("\"recordType\":\"manifest\""));
		assertTrue(lines.getLast().contains("export_complete"));
	}

	@Test
	void aSessionChangeLeavesAnExplicitlyIncompleteExport() throws Exception {
		int[] count = {0};
		MinecraftTransport transport = (method, path, body) -> ++count[0] == 1 ? page("one", true) : page("two", false);
		Path output = directory.resolve("interrupted.jsonl");
		assertThrows(java.io.IOException.class, () -> new LiveRecordingExporter(transport, new ObjectMapper()).export(output,
			"/v1/agent/debug/recording?mode=query&since=0", false));
		assertFalse(Files.readString(output).contains("export_complete"));
	}

	private static Map<String, Object> page(String session, boolean first) {
		Map<String, Object> page = new LinkedHashMap<>();
		page.put("sessionId", session);
		page.put("throughSequence", 9L);
		page.put("fromServerTickId", 20L);
		page.put("toServerTickId", 50L);
		page.put("nextCursor", first ? 4L : 9L);
		page.put("hasMore", first);
		page.put("observations", List.of(Map.of("recordType", "observation", "sequence", first ? 4L : 9L)));
		return page;
	}
}
