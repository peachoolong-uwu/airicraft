package ai.moeru.airicraft.agent.recording;

import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Base64;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PlaytestVideoRecorderTest {
	@TempDir Path directory;

	@Test void encodesTwentyHzWithFractionalSecondAnchorsAndGapHolds() throws Exception {
		var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
		var bytes = new ByteArrayOutputStream();
		ImageIO.write(image, "jpeg", bytes);
		String jpeg = Base64.getEncoder().encodeToString(bytes.toByteArray());
		try (var recorder = new PlaytestVideoRecorder(directory)) {
			for (int millis : new int[]{0, 50, 200}) {
				var frame = new com.google.gson.JsonObject();
				frame.addProperty("capturedAtMs", 1000 + millis);
				frame.addProperty("serverTickId", millis / 50);
				var payload = new com.google.gson.JsonObject();
				payload.addProperty("imageBase64", jpeg);
				frame.add("payload", payload);
				recorder.append(frame);
			}
		}
		var anchors = Files.readAllLines(directory.resolve("screen-frames.jsonl"));
		assertEquals(0.05, JsonParser.parseString(anchors.get(1)).getAsJsonObject().get("videoSeconds").getAsDouble(), 0.001);
		assertEquals(0.2, JsonParser.parseString(anchors.get(2)).getAsJsonObject().get("videoSeconds").getAsDouble(), 0.001);
		var probe = new ProcessBuilder("ffprobe", "-v", "error", "-count_frames", "-show_entries",
				"stream=r_frame_rate,nb_read_frames,duration", "-of", "json", directory.resolve("screen.mp4").toString()).start();
		String output = new String(probe.getInputStream().readAllBytes());
		assertEquals(0, probe.waitFor());
		var stream = JsonParser.parseString(output).getAsJsonObject().getAsJsonArray("streams").get(0).getAsJsonObject();
		assertEquals("20/1", stream.get("r_frame_rate").getAsString());
		assertEquals(5, stream.get("nb_read_frames").getAsInt());
	}
}
