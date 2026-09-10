package ai.moeru.airicraft.agent.memory;

import ai.moeru.airicraft.Airicraft;
import baritone.api.BaritoneAPI;
import baritone.api.utils.input.Input;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.util.List;

/** Client-thread publication, immutable reads on Baritone's path calculation thread. */
public final class WorldPlacePreservation {
	private static volatile Snapshot current = new Snapshot(null, List.of(), false);

	private WorldPlacePreservation() {}

	public static void tick(MinecraftClient client) {
		if (current.world() != client.world) reload(client);
	}

	public static void clear() {
		current = new Snapshot(null, List.of(), false);
	}

	public static void reload(MinecraftClient client) {
		if (client.world == null || client.getServer() == null) {
			current = new Snapshot(client.world, List.of(), false);
			return;
		}
		try {
			List<PlaceMemory.Place> places = new PlaceMemory(client.getServer().getSavePath(WorldSavePath.ROOT)).list();
			current = Snapshot.from(client.world, client.world.getRegistryKey().getValue().toString(), places);
		}
		catch (IOException exception) {
			// A broken memory file must not silently turn a built home into available resources.
			if (current.world() != client.world) current = new Snapshot(client.world, List.of(), true);
			Airicraft.LOGGER.error("Cannot load preserved places; automatic terrain edits remain restricted", exception);
		}
	}

	public static java.util.Map<String, Object> debugSnapshot() {
		Snapshot snapshot = current;
		return java.util.Map.of("areas", snapshot.areas(), "unavailable", snapshot.unavailable());
	}

	public static boolean contains(Object world, int x, int y, int z) {
		return current.contains(world, x, y, z);
	}

	public static boolean contains(Object world, BlockPos pos) {
		return contains(world, pos.getX(), pos.getY(), pos.getZ());
	}

	/** Also guards an already-calculated path when a preserved area is added while it runs. */
	public static boolean blocksPathBreaking(BlockPos pos) {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.world != null && contains(client.world, pos)
			&& BaritoneAPI.getProvider().getPrimaryBaritone().getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT);
	}

	record Snapshot(Object world, List<PlaceMemory.PreservedArea> areas, boolean unavailable) {
		Snapshot { areas = List.copyOf(areas); }

		static Snapshot from(Object world, String dimension, List<PlaceMemory.Place> places) {
			return new Snapshot(world, places.stream().filter(place -> place.dimension().equals(dimension))
				.map(PlaceMemory.Place::preserveArea).filter(java.util.Objects::nonNull).toList(), false);
		}

		boolean contains(Object world, int x, int y, int z) {
			if (this.world == null || this.world != world) return false;
			if (unavailable) return true;
			for (PlaceMemory.PreservedArea area : areas) if (area.contains(x, y, z)) return true;
			return false;
		}
	}
}
