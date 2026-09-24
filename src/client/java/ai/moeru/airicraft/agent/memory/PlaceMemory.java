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

/** Fallback file backend only. Application consumers use LocationMemoryService. */
public final class PlaceMemory implements LocationMemoryProvider {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int MAX_PLACES = 256;
	private final Path file;

	public PlaceMemory(Path worldDirectory) {
		file = worldDirectory.resolve("airicraft/places.json");
	}

	@Override
	public String id() { return "airicraft"; }

	public List<Place> list() throws IOException { return read().places(); }

	private Document read() throws IOException {
		String json;
		try {
			json = Files.readString(file);
		}
		catch (NoSuchFileException exception) {
			return new Document(1, List.of(), java.util.Map.of());
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
			java.util.Map<String, String> ids = new java.util.LinkedHashMap<>();
			HashSet<String> seenIds = new HashSet<>();
			for (Place place : document.places()) {
				String id = document.ids() == null ? legacyId(place.name()) : document.ids().getOrDefault(place.name(), legacyId(place.name()));
				if (id == null || id.isBlank() || !seenIds.add(id)) throw new IllegalArgumentException("invalid or duplicate location id");
				ids.put(place.name(), id);
			}
			return new Document(1, document.places().stream().sorted(Comparator.comparing(Place::name)).toList(), ids);
		}
		catch (RuntimeException exception) {
			throw new IOException("invalid_place_memory: " + exception.getMessage(), exception);
		}
	}

	public Optional<Place> recall(String name) throws IOException {
		return list().stream().filter(place -> place.name().equals(name)).findFirst();
	}

	public void remember(Place place) throws IOException {
		var existing = listLocations().stream().filter(value -> value.name().equals(place.name())).findFirst();
		save(existing.map(Location::id).orElse(null), place);
	}

	public boolean forget(String name) throws IOException {
		var existing = listLocations().stream().filter(value -> value.name().equals(name)).findFirst();
		return existing.isPresent() && delete(existing.get().id());
	}

	@Override
	public List<Location> listLocations() throws IOException {
		Document document = read();
		return document.places().stream().map(place -> Location.from(document.ids().get(place.name()), place)).toList();
	}

	@Override
	public Location save(String existingId, Place place) throws IOException {
		Document document = read();
		String previousName = existingId == null ? null : document.ids().entrySet().stream()
			.filter(entry -> entry.getValue().equals(existingId)).map(java.util.Map.Entry::getKey).findFirst()
			.orElseThrow(() -> new IllegalArgumentException("place_not_found: " + existingId));
		ArrayList<Place> places = new ArrayList<>(document.places());
		if (places.stream().anyMatch(value -> value.name().equals(place.name()) && !value.name().equals(previousName))) {
			throw new IllegalArgumentException("place_name_exists: " + place.name());
		}
		places.removeIf(value -> value.name().equals(previousName));
		if (places.size() >= MAX_PLACES) throw new IOException("place_memory_full: forget a place before adding another");
		places.add(place);
		var ids = new java.util.LinkedHashMap<>(document.ids());
		if (previousName != null) ids.remove(previousName);
		String id = existingId == null ? java.util.UUID.randomUUID().toString() : existingId;
		ids.put(place.name(), id);
		write(places, ids);
		return Location.from(id, place);
	}

	@Override
	public boolean delete(String id) throws IOException {
		Document document = read();
		var places = new ArrayList<>(document.places());
		if (!places.removeIf(value -> id.equals(document.ids().get(value.name())))) return false;
		var ids = new java.util.LinkedHashMap<>(document.ids());
		ids.values().removeIf(id::equals);
		write(places, ids);
		return true;
	}

	private static String legacyId(String name) {
		return java.util.UUID.nameUUIDFromBytes(("airicraft.place:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
	}

	private void write(List<Place> places, java.util.Map<String, String> ids) throws IOException {
		Files.createDirectories(file.getParent());
		Path temporary = Files.createTempFile(file.getParent(), "places-", ".tmp");
		try {
			Files.writeString(temporary, GSON.toJson(new Document(1,
				places.stream().sorted(Comparator.comparing(Place::name)).toList(), ids)) + "\n");
			Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		finally {
			Files.deleteIfExists(temporary);
		}
	}

	private record Document(int version, List<Place> places, java.util.Map<String, String> ids) {}

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
