package ai.moeru.airicraft.os;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Append-only evidence. Pressure stops admissions instead of erasing earlier decisions. */
final class DecisionTrace implements AutoCloseable {
	private static final long SEGMENT_BYTES = 16 * 1024 * 1024;
	private static final long BUDGET_BYTES = 4 * SEGMENT_BYTES, CLEANUP_BYTES = 1024 * 1024;
	private final Path directory;
	private final String run = UUID.randomUUID().toString();
	private FileChannel file;
	private int segment;
	private long sequence, position;
	private String failure;
	DecisionTrace(Path root) throws IOException {
		Files.createDirectories(root);
		try (var runs = Files.list(root)) {
			if (runs.filter(Files::isDirectory).limit(8).count() >= 8) throw new IOException("trace_run_capacity");
		}
		directory = root.resolve(run); Files.createDirectory(directory); open();
	}
	void record(String type, JsonObject data) throws IOException { record(type, data, false); }
	void record(String type, JsonObject data, boolean cleanup) throws IOException {
		try {
			if (file == null) throw new IOException("trace_closed");
			if (failure != null && !cleanup) throw new IOException(failure);
			byte[] bytes = (OsJson.canonical(OsJson.obj("run", run, "sequence", sequence + 1, "atMillis", System.currentTimeMillis(), "type", type,
				"data", OsJson.copy(data, 65_536, 24, 8192))) + "\n").getBytes(StandardCharsets.UTF_8);
			if (bytes.length > 65_536) throw new IOException("trace_event_limit");
			long next = position;
			if (file.position() + bytes.length > SEGMENT_BYTES) next = (segment + 1L) * SEGMENT_BYTES;
			if (next + bytes.length > BUDGET_BYTES - (cleanup ? 0 : CLEANUP_BYTES)) throw new IOException("trace_budget_exhausted");
			if (next != position) { file.force(false); file.close(); segment++; open(); }
			var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) file.write(buffer);
			position = next + bytes.length; sequence++;
		} catch (IOException error) {
			if (failure == null) failure = OsJson.reason(error);
			if (!cleanup) throw error;
		}
	}
	private void open() throws IOException { file = FileChannel.open(directory.resolve("trace-" + segment + ".jsonl"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); }
	@Override public void close() throws IOException {
		if (file == null) return;
		try { record("trace.closed", OsJson.obj("complete", failure == null, "reason", failure), true); file.force(false); }
		finally { file.close(); file = null; }
	}
}
