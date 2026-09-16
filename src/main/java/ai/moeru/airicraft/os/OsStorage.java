package ai.moeru.airicraft.os;

import com.google.gson.JsonElement;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Crash-safe bounded files; all callers execute off the Minecraft thread. */
final class OsStorage {
	private OsStorage() {}
	static JsonElement read(Path file, int maximum) throws IOException {
		try (var stream = Files.newInputStream(file)) {
			byte[] bytes = stream.readNBytes(maximum + 1);
			if (bytes.length > maximum) throw new IOException("storage_limit");
			return OsJson.parse(new String(bytes, StandardCharsets.UTF_8), maximum, 32, 65_536);
		}
	}
	static void write(Path file, JsonElement value, int maximum) throws IOException {
		byte[] bytes = OsJson.canonical(OsJson.copy(value, maximum, 32, 65_536)).getBytes(StandardCharsets.UTF_8);
		Files.createDirectories(file.getParent());
		Path temporary = file.resolveSibling("." + file.getFileName() + "-" + UUID.randomUUID() + ".tmp");
		try {
			try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
				var buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining()) channel.write(buffer);
				channel.force(true);
			}
			Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			try (var directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) { directory.force(true); }
		} finally { Files.deleteIfExists(temporary); }
	}
}
