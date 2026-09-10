package ai.moeru.airicraft.agent.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** Explicit agent bookmarks. A coordinate is a destination, not a claim of safety or reachability. */
public final class PlaceMemory {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int MAX_PLACES = 256;
	private final Path file;

	public PlaceMemory(Path worldDirectory) {
		file = worldDirectory.resolve("airicraft/places.json");
	}

	public List<Place> list() throws IOException {
		String json;
		try {
			json = Files.readString(file);
		}
		catch (NoSuchFileException exception) {
			return List.of();
		}
		try {
			Document document = GSON.fromJson(json, Document.class);
			if (document == null || document.version() != 1 || document.places() == null) {
				throw new IllegalArgumentException("unsupported or missing place memory version");
			}
			if (document.places().size() > MAX_PLACES) {
				throw new IllegalArgumentException("too many places");
			}
			HashSet<String> names = new HashSet<>();
			for (Place place : document.places()) {
				if (place == null || !names.add(place.name())) {
					throw new IllegalArgumentException("null or duplicate place");
				}
			}
			return document.places().stream().sorted(Comparator.comparing(Place::name)).toList();
		}
		catch (RuntimeException exception) {
			throw new IOException("invalid_place_memory: " + exception.getMessage(), exception);
		}
	}

	public Optional<Place> recall(String name) throws IOException {
		return list().stream().filter(place -> place.name().equals(name)).findFirst();
	}

	public void remember(Place place) throws IOException {
		ArrayList<Place> places = new ArrayList<>(list());
		places.removeIf(existing -> existing.name().equals(place.name()));
		if (places.size() >= MAX_PLACES) {
			throw new IOException("place_memory_full: forget a place before adding another");
		}
		places.add(place);
		write(places);
	}

	public boolean forget(String name) throws IOException {
		ArrayList<Place> places = new ArrayList<>(list());
		if (!places.removeIf(place -> place.name().equals(name))) {
			return false;
		}
		write(places);
		return true;
	}

	private void write(List<Place> places) throws IOException {
		Files.createDirectories(file.getParent());
		Path temporary = Files.createTempFile(file.getParent(), "places-", ".tmp");
		try {
			Files.writeString(temporary, GSON.toJson(new Document(1,
				places.stream().sorted(Comparator.comparing(Place::name)).toList())) + "\n");
			Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		finally {
			Files.deleteIfExists(temporary);
		}
	}

	private record Document(int version, List<Place> places) {}

	public record Place(String name, String dimension, int x, int y, int z, String note, PreservedArea preserveArea) {
		public Place(String name, String dimension, int x, int y, int z, String note) {
			this(name, dimension, x, y, z, note, null);
		}
		public Place {
			name = checkedText(name, "name", 128, false);
			dimension = checkedText(dimension, "dimension", 256, false);
			if (!dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
				throw new IllegalArgumentException("dimension must be a namespaced dimension id");
			}
			note = checkedText(note, "note", 2048, true);
		}
	}

	/** Inclusive bounds in the remembered place's dimension. Exact construction tools can still edit them. */
	public record PreservedArea(int x1, int y1, int z1, int x2, int y2, int z2) {
		public PreservedArea {
			if (x1 > x2 || y1 > y2 || z1 > z2) throw new IllegalArgumentException("preserveArea minimum must not exceed maximum");
		}

		public boolean contains(int x, int y, int z) {
			return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
		}
	}

	public static String checkedText(String value, String field, int maxLength, boolean allowEmpty) {
		if (value == null || (!allowEmpty && value.isBlank()) || value.length() > maxLength) {
			throw new IllegalArgumentException(field + " must be " + (allowEmpty ? "0" : "1") + ".." + maxLength + " characters");
		}
		return value.trim();
	}
}
