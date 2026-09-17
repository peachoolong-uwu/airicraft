package ai.moeru.airicraft.agent.recording;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Encodes sampled framebuffer JPEGs off the client thread, with independently flushed MP4 fragments. */
final class PlaytestVideoRecorder implements AutoCloseable {
	private static final Gson GSON = new Gson();
	private final Path directory;
	private final ArrayBlockingQueue<JsonObject> frames = new ArrayBlockingQueue<>(16);
	private volatile boolean closing;
	private volatile IOException failure;
	private Thread worker;
	private Process process;

	PlaytestVideoRecorder(Path directory) { this.directory = directory; }

	void append(JsonObject observation) throws IOException {
		checkFailure();
		if (closing) throw new IOException("Screen recording is already closed");
		if (worker == null) {
			worker = new Thread(this::encode, "airicraft-playtest-video");
			worker.setDaemon(true);
			worker.start();
		}
		if (!frames.offer(observation.deepCopy())) throw new IOException("Screen encoder fell behind; recording is incomplete");
	}

	void checkFailure() throws IOException { if (failure != null) throw failure; }

	private void encode() {
		try {
			process = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "warning", "-n",
				"-probesize", "32", "-analyzeduration", "0", "-f", "image2pipe", "-framerate", "1", "-c:v", "mjpeg", "-i", "pipe:0",
				"-an", "-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency", "-crf", "23",
				"-pix_fmt", "yuv420p", "-g", "2", "-bf", "0",
				"-movflags", "+frag_keyframe+empty_moov+default_base_moof", "-flush_packets", "1",
				directory.resolve("screen.mp4").toString())
				.redirectError(directory.resolve("screen-encoder.log").toFile())
				.redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
			Files.writeString(directory.resolve("screen-encoder.json"), GSON.toJson(Map.of("pid", process.pid())) + "\n");
			try (var input = process.getOutputStream();
				var index = Files.newBufferedWriter(directory.resolve("screen-frames.jsonl"), StandardOpenOption.CREATE_NEW)) {
				long firstTime = -1;
				long startedNanos = 0;
				long frameNumber = 0;
				byte[] previous = null;
				while (!closing || !frames.isEmpty()) {
					JsonObject frame = frames.poll(100, TimeUnit.MILLISECONDS);
					if (frame == null) {
						// Identical images are suppressed by the dashboard. Keep recording their held image,
						// including quiet periods, so a crash still leaves recently flushed fragments.
						if (previous != null && frameNumber <= (System.nanoTime() - startedNanos) / 1_000_000_000L) {
							input.write(previous);
							input.flush();
							frameNumber++;
						}
						continue;
					}
					long capturedAt = frame.get("capturedAtMs").getAsLong();
					if (firstTime < 0) { firstTime = capturedAt; startedNanos = System.nanoTime(); }
					// Hold the previous sampled image across gaps, preserving elapsed capture time.
					long target = Math.max(frameNumber, Math.round((capturedAt - firstTime) / 1000.0));
					while (previous != null && frameNumber < target) { input.write(previous); frameNumber++; }
					previous = Base64.getDecoder().decode(frame.getAsJsonObject("payload").get("imageBase64").getAsString());
					input.write(previous);
					input.flush();
					index.write(GSON.toJson(Map.of("videoSeconds", frameNumber,
						"serverTickId", frame.get("serverTickId").getAsLong(), "capturedAtMs", capturedAt)) + "\n");
					index.flush();
					frameNumber++;
				}
			}
			if (!process.waitFor(15, TimeUnit.SECONDS)) throw new IOException("Screen encoder did not finish");
			if (process.exitValue() != 0) throw new IOException("Screen encoder failed; see screen-encoder.log");
		}
		catch (Exception error) {
			failure = new IOException("Cannot record playtest screen", error);
		}
		finally {
			if (process != null && process.isAlive()) process.destroyForcibly();
		}
	}

	@Override public void close() throws IOException {
		closing = true;
		if (worker != null) {
			try { worker.join(20_000); }
			catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
			if (worker.isAlive()) {
				if (process != null) process.destroyForcibly();
				throw new IOException("Screen encoder did not close; fragments already written are retained");
			}
		}
		checkFailure();
	}
}
