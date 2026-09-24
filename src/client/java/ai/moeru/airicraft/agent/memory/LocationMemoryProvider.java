package ai.moeru.airicraft.agent.memory;

import java.io.IOException;
import java.util.List;

/** Current-world location storage. Implementations are called on the Minecraft client thread. */
public interface LocationMemoryProvider {
	String id();
	default boolean available() { return true; }
	List<Location> listLocations() throws IOException;
	Location save(String existingId, PlaceMemory.Place place) throws IOException;
	boolean delete(String id) throws IOException;

	record Location(String id, String name, String dimension, int x, int y, int z,
		String note, PlaceMemory.PreservedArea preserveArea) {
		public static Location from(String id, PlaceMemory.Place place) {
			return new Location(id, place.name(), place.dimension(), place.x(), place.y(), place.z(), place.note(), place.preserveArea());
		}
	}
}
