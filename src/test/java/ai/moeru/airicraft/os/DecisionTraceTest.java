package ai.moeru.airicraft.os;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DecisionTraceTest {
	@TempDir Path directory;
	@Test void restartsPreserveEarlierEvidenceAndDoNotGrowWithoutABound() throws Exception {
		for (int run = 0; run < 8; run++) {
			try (var trace = new DecisionTrace(directory)) { trace.record("check", OsJson.obj("index", run)); }
		}
		assertThrows(java.io.IOException.class, () -> new DecisionTrace(directory));
		try (var paths = Files.walk(directory)) {
			var logs = paths.filter(Files::isRegularFile).toList(); assertEquals(8, logs.size());
			var indices = new java.util.HashSet<Integer>();
			for (var log : logs) {
				var events = Files.readAllLines(log); assertEquals(2, events.size());
				indices.add(OsJson.parse(events.getFirst()).getAsJsonObject().getAsJsonObject("data").get("index").getAsInt());
			}
			assertEquals(8, indices.size());
		}
	}
}
