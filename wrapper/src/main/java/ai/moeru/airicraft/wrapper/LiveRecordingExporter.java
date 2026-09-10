package ai.moeru.airicraft.wrapper;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class LiveRecordingExporter {
	private final MinecraftTransport transport;
	private final ObjectMapper mapper;

	LiveRecordingExporter(MinecraftTransport transport, ObjectMapper mapper) {
		this.transport = transport;
		this.mapper = mapper;
	}

	Map<String, Object> export(Path output, String queryPath, boolean frames) throws IOException {
		String path = queryPath + "&images=" + frames;
		Map<String, Object> page = transport.get(path);
		Object session = page.get("sessionId");
		long through = ((Number) page.get("throughSequence")).longValue();
		long from = ((Number) page.get("fromServerTickId")).longValue();
		long to = ((Number) page.get("toServerTickId")).longValue();
		Path target = output.toAbsolutePath().normalize();
		Files.createDirectories(target.getParent());
		long count = 0L;
		boolean truncated = Boolean.TRUE.equals(page.get("truncated"));
		// CREATE_NEW protects an earlier captured incident from accidental replacement.
		try (var writer = Files.newBufferedWriter(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			Map<String, Object> manifest = new LinkedHashMap<>(page);
			manifest.remove("observations");
			manifest.put("recordType", "manifest");
			manifest.put("includesFrames", frames);
			writer.write(mapper.writeValueAsString(manifest));
			writer.newLine();
			while (true) {
				if (!Objects.equals(session, page.get("sessionId"))) {
					throw new IOException("Recording session changed during export; the partial file is not a complete incident");
				}
				truncated |= Boolean.TRUE.equals(page.get("truncated"));
				for (Object observation : (List<?>) page.get("observations")) {
					writer.write(mapper.writeValueAsString(observation));
					writer.newLine();
					count++;
				}
				if (!Boolean.TRUE.equals(page.get("hasMore"))) {
					break;
				}
				long cursor = ((Number) page.get("nextCursor")).longValue();
				String next = path.replaceAll("([?&])since=[^&]*", "$1since=" + cursor)
					.replaceAll("&from=[^&]*", "").replaceAll("&to=[^&]*", "");
				page = transport.get(next + "&from=" + from + "&to=" + to + "&through=" + through);
			}
			writer.write(mapper.writeValueAsString(Map.of("recordType", "export_complete", "observations", count, "truncated", truncated)));
			writer.newLine();
		}
		return Map.of("outputPath", target.toString(), "observations", count, "includesFrames", frames, "truncated", truncated);
	}
}
