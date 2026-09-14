package ai.moeru.airicraft.agent.memory;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** One CRUD contract, independent of the active storage backend. Names never resolve arbitrarily. */
public final class LocationMemoryService {
	private final LocationMemoryProvider provider;

	public LocationMemoryService(LocationMemoryProvider provider) {
		this.provider = java.util.Objects.requireNonNull(provider);
	}

	public String backend() { return provider.id(); }

	public List<LocationMemoryProvider.Location> list() throws IOException {
		if (!provider.available()) throw new IllegalStateException("location_backend_unavailable: " + backend());
		return provider.listLocations().stream().sorted(Comparator.comparing(LocationMemoryProvider.Location::name)
			.thenComparing(LocationMemoryProvider.Location::id)).toList();
	}

	public LocationMemoryProvider.Location recall(String id, String name) throws IOException {
		return resolve(id, name).orElseThrow(() -> new IllegalArgumentException("place_not_found: " + (id == null ? name : id)));
	}

	public LocationMemoryProvider.Location remember(String id, PlaceMemory.Place place) throws IOException {
		var previous = id == null ? resolve(null, place.name()) : Optional.of(recall(id, null));
		var saved = provider.save(previous.map(LocationMemoryProvider.Location::id).orElse(null), place);
		LocationMemoryBridge.changed();
		return saved;
	}

	public boolean forget(String id, String name) throws IOException {
		var previous = resolve(id, name);
		boolean deleted = previous.isPresent() && provider.delete(previous.get().id());
		if (deleted) LocationMemoryBridge.changed();
		return deleted;
	}

	private Optional<LocationMemoryProvider.Location> resolve(String id, String name) throws IOException {
		var matches = list().stream().filter(value -> id != null ? value.id().equals(id) : value.name().equals(name)).toList();
		if (matches.size() > 1) {
			throw new IllegalArgumentException("ambiguous_place: " + name + "; use id from "
				+ matches.stream().map(LocationMemoryProvider.Location::id).toList());
		}
		return matches.stream().findFirst();
	}
}
