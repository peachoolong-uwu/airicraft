package ai.moeru.airicraft.agent.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Detached observations captured on the client thread. Guest code only receives data. */
record WorldQuerySnapshot(JsonObject data, List<BlockPos> observedPositions, ClientWorld world) {
	private static final Gson JSON = new Gson();
	private static final int MAX_ENTITIES = 64;

	static void validateBounds(JsonObject args) {
		if (!Set.of("source", "input", "center", "radius", "verticalRadius", "includeBlocks", "includeEntities").containsAll(args.keySet()))
			throw new IllegalArgumentException("unknown_query_argument");
		integer(args, "radius", 4, 0, 8);
		integer(args, "verticalRadius", 2, 0, 4);
		for (String key : List.of("includeBlocks", "includeEntities")) {
			if (args.has(key) && (!args.get(key).isJsonPrimitive() || !args.getAsJsonPrimitive(key).isBoolean()))
				throw new IllegalArgumentException(key + " must be boolean");
		}
		if (args.has("center")) {
			if (!args.get("center").isJsonObject() || !args.getAsJsonObject("center").keySet().equals(Set.of("x", "y", "z")))
				throw new IllegalArgumentException("center must contain integer x,y,z");
			for (String axis : List.of("x", "y", "z")) integer(args.getAsJsonObject("center"), axis, 0, -30_000_000, 30_000_000);
		}
	}

	static WorldQuerySnapshot capture(MinecraftClient client, JsonObject args, long serverTick) {
		validateBounds(args);
		if (client.world == null || client.player == null) throw new IllegalStateException("world_not_loaded");
		var world = client.world;
		var player = client.player;
		BlockPos origin = player.getBlockPos();
		BlockPos center = origin;
		if (args.has("center")) {
			var c = args.getAsJsonObject("center");
			center = new BlockPos(c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("z").getAsInt());
		}
		int radius = integer(args, "radius", 4, 0, 8), vertical = integer(args, "verticalRadius", 2, 0, 4);
		BlockPos min = center.add(-radius, -vertical, -radius), max = center.add(radius, vertical, radius);
		// Check the farthest corner without subtracting in 32-bit coordinate arithmetic.
		double dx = Math.abs((double) center.getX() - origin.getX()) + radius;
		double dy = Math.abs((double) center.getY() - origin.getY()) + vertical;
		double dz = Math.abs((double) center.getZ() - origin.getZ()) + radius;
		if (dx * dx + dy * dy + dz * dz > 64 * 64) throw new IllegalArgumentException("query_bounds_too_far maxDistance=64");
		boolean includeBlocks = !args.has("includeBlocks") || args.get("includeBlocks").getAsBoolean();
		boolean includeEntities = !args.has("includeEntities") || args.get("includeEntities").getAsBoolean();
		JsonArray blocks = new JsonArray(), entities = new JsonArray();
		List<BlockPos> observed = new ArrayList<>();
		int unloaded = 0, outsideWorld = 0;
		if (includeBlocks) for (BlockPos cursor : BlockPos.iterate(min, max)) {
			if (world.isOutOfHeightLimit(cursor)) { outsideWorld++; continue; }
			if (!world.isChunkLoaded(cursor)) { unloaded++; continue; }
			BlockPos pos = cursor.toImmutable();
			var state = world.getBlockState(pos);
			JsonObject block = new JsonObject();
			block.add("position", JSON.toJsonTree(position(pos)));
			block.addProperty("blockId", Registries.BLOCK.getId(state.getBlock()).toString());
			JsonObject properties = new JsonObject();
			state.getEntries().forEach((property, value) -> properties.addProperty(property.getName(), value.toString()));
			block.add("properties", properties);
			block.addProperty("air", state.isAir());
			block.addProperty("replaceable", state.isReplaceable());
			block.addProperty("fluid", !state.getFluidState().isEmpty());
			block.addProperty("collisionEmpty", state.getCollisionShape(world, pos).isEmpty());
			block.add("collisionBoxes", JSON.toJsonTree(state.getCollisionShape(world, pos).getBoundingBoxes().stream()
				.map(b -> List.of(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)).toList()));
			block.addProperty("light", world.getLightLevel(pos));
			blocks.add(block);
			observed.add(pos);
		}
		int matchedEntities = 0;
		if (includeEntities) {
			var box = new Box(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
			var found = world.getOtherEntities(player, box);
			matchedEntities = found.size();
			found.stream().sorted(Comparator.comparingDouble((Entity entity) -> entity.squaredDistanceTo(player))
				.thenComparing(Entity::getUuidAsString)).limit(MAX_ENTITIES).forEach(entity -> {
				JsonObject record = new JsonObject();
				record.addProperty("uuid", entity.getUuidAsString());
				record.addProperty("type", Registries.ENTITY_TYPE.getId(entity.getType()).toString());
				record.add("position", JSON.toJsonTree(position(entity.getPos())));
				record.addProperty("distance", Math.sqrt(entity.squaredDistanceTo(player)));
				record.addProperty("hostile", entity.getType().getSpawnGroup() == SpawnGroup.MONSTER);
				record.addProperty("alive", entity.isAlive());
				if (entity instanceof LivingEntity living) record.addProperty("health", living.getHealth());
				entities.add(record);
			});
		}
		int requestedBlocks = includeBlocks ? (2 * radius + 1) * (2 * radius + 1) * (2 * vertical + 1) : 0;
		JsonObject metadata = JSON.toJsonTree(Map.of(
			"source", "client_loaded_snapshot", "serverTick", serverTick, "worldTime", world.getTime(),
			"dimension", world.getRegistryKey().getValue().toString(), "bounds", Map.of("min", position(min), "max", position(max)),
			"blocks", Map.of("included", includeBlocks, "requested", requestedBlocks, "returned", blocks.size(), "unloaded", unloaded,
				"outsideWorld", outsideWorld, "truncated", false),
			"entities", Map.of("included", includeEntities, "matched", matchedEntities, "returned", entities.size(),
				"truncated", matchedEntities > entities.size(), "order", "nearest_player")
		)).getAsJsonObject();
		JsonObject snapshot = new JsonObject();
		snapshot.add("metadata", metadata);
		snapshot.add("player", JSON.toJsonTree(Map.of("position", position(player.getPos()), "health", player.getHealth(),
			"food", player.getHungerManager().getFoodLevel())));
		snapshot.add("blocks", blocks);
		snapshot.add("entities", entities);
		return new WorldQuerySnapshot(snapshot, List.copyOf(observed), world);
	}

	private static Map<String, Integer> position(BlockPos pos) { return Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ()); }
	private static Map<String, Double> position(Vec3d pos) { return Map.of("x", pos.x, "y", pos.y, "z", pos.z); }
	private static int integer(JsonObject args, String key, int fallback, int min, int max) {
		if (!args.has(key)) return fallback;
		try {
			if (!args.get(key).isJsonPrimitive() || !args.getAsJsonPrimitive(key).isNumber()) throw new ArithmeticException();
			int value = args.get(key).getAsBigDecimal().intValueExact();
			if (value < min || value > max) throw new ArithmeticException();
			return value;
		} catch (RuntimeException error) { throw new IllegalArgumentException(key + " must be integer " + min + ".." + max); }
	}
}
