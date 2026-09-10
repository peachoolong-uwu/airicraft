package ai.moeru.airicraft.dashboard;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

final class DashboardLogTailer {
	private static final int MAX_LINE_CHARS = 64 * 1024;

	private final Path logPath;
	private final DashboardObservationStore store;
	private long offset;

	DashboardLogTailer(Path logPath, DashboardObservationStore store) {
		this.logPath = logPath;
		this.store = store;
	}

	void startAtTail() {
		try {
			offset = Files.exists(logPath) ? Files.size(logPath) : 0L;
		}
		catch (IOException ignored) {
			offset = 0L;
		}
	}

	void poll() {
		if (!Files.isRegularFile(logPath)) {
			return;
		}
		try (RandomAccessFile file = new RandomAccessFile(logPath.toFile(), "r")) {
			if (file.length() < offset) {
				offset = 0L;
			}
			file.seek(offset);
			String line;
			while ((line = file.readLine()) != null) {
				String decoded = new String(line.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), java.nio.charset.StandardCharsets.UTF_8);
				if (decoded.isBlank()) {
					continue;
				}
				if (decoded.length() > MAX_LINE_CHARS) {
					decoded = decoded.substring(0, MAX_LINE_CHARS) + " …[truncated]";
				}
				store.appendLog(decoded, System.currentTimeMillis());
			}
			offset = file.getFilePointer();
		}
		catch (IOException ignored) {
			// The log file can rotate while it is being read. The next poll retries from the last good offset.
		}
	}
}
