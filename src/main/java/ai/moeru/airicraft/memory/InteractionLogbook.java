package ai.moeru.airicraft.memory;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/** Append-only world history. Gameplay tools can query it, never author or delete entries. */
public final class InteractionLogbook {
	private static final Gson GSON = new Gson();
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "airicraft-logbook");
		thread.setDaemon(true);
		return thread;
	});
	private static volatile String lastWriteError;

	public record Entry(long timestampMs, long worldTick, String actor, String dimension,
		int x, int y, int z, String action, String itemId, int count, String containerType, Map<String, Integer> contents) {
		public Entry { contents = contents == null ? Map.of() : Map.copyOf(contents); }
	}

	public static void record(Path worldDirectory, List<Entry> entries) {
		if (entries.isEmpty()) return;
		List<Entry> batch = List.copyOf(entries);
		IO.execute(() -> {
			try { append(worldDirectory, batch); }
			catch (IOException exception) {
				lastWriteError = exception.getMessage();
				ai.moeru.airicraft.Airicraft.LOGGER.error("Interaction logbook write failed", exception);
			}
		});
	}

	public static CompletableFuture<List<Entry>> query(Path worldDirectory, Predicate<Entry> filter, int limit) {
		return CompletableFuture.supplyAsync(() -> {
			if (lastWriteError != null) throw new IllegalStateException("logbook_write_failed: " + lastWriteError);
			try { return read(worldDirectory, filter, limit); }
			catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
		}, IO);
	}

	/** Keep later empty snapshots for containers that previously held the searched item. */
	public static Predicate<Entry> matchingItemHistory(String item) {
		java.util.Set<String> knownContainers = new java.util.HashSet<>();
		return entry -> {
			if (item.isEmpty()) return true;
			String key = entry.actor() + "/" + entry.dimension() + "/" + entry.x() + "," + entry.y() + "," + entry.z();
			boolean matches = entry.itemId().equals(item) || entry.contents().containsKey(item);
			if (matches && !entry.containerType().isEmpty()) knownContainers.add(key);
			return matches || entry.action().equals("container_observed") && knownContainers.contains(key);
		};
	}

	public static void flush() { CompletableFuture.runAsync(() -> {}, IO).join(); }

	static void append(Path worldDirectory, List<Entry> entries) throws IOException {
		Path file = worldDirectory.resolve("airicraft/interactions.jsonl");
		Files.createDirectories(file.getParent());
		StringBuilder batch = new StringBuilder();
		for (Entry entry : entries) batch.append(GSON.toJson(entry)).append('\n');
		Files.writeString(file, batch, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	static List<Entry> read(Path worldDirectory, Predicate<Entry> filter, int limit) throws IOException {
		if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
		Path file = worldDirectory.resolve("airicraft/interactions.jsonl");
		if (!Files.exists(file)) return List.of();
		ArrayDeque<Entry> recent = new ArrayDeque<>();
		try (var reader = Files.newBufferedReader(file)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) continue;
				Entry entry = GSON.fromJson(line, Entry.class);
				if (entry != null && filter.test(entry)) {
					if (recent.size() == limit) recent.removeFirst();
					recent.addLast(entry);
				}
			}
		}
		return List.copyOf(recent);
	}
}
